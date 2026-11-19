package me.virul.nexora.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import me.virul.nexora.core.capability.CapabilityResult;
import me.virul.nexora.core.execution.ExecutionHandle;
import me.virul.nexora.core.execution.ExecutionResult;
import me.virul.nexora.core.execution.ExecutionStatus;
import me.virul.nexora.core.intent.Intent;
import me.virul.nexora.event.EngineShutdownEvent;
import me.virul.nexora.event.PlanCancelledEvent;
import me.virul.nexora.persistence.ExecutionRecord;
import me.virul.nexora.persistence.ExecutionState;
import me.virul.nexora.persistence.jdbc.JdbcExecutionStore;
import me.virul.nexora.planner.model.StepDefinition;
import me.virul.nexora.runtime.engine.DrainResult;
import me.virul.nexora.runtime.engine.EngineShuttingDownException;
import me.virul.nexora.spi.Capability;
import me.virul.nexora.spi.CapabilityDescriptor;
import me.virul.nexora.spi.CapabilityProvider;
import me.virul.nexora.spi.NexoraPlugin;
import me.virul.nexora.spi.PluginContext;
import me.virul.nexora.spi.PluginDescriptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GracefulShutdownIntegrationTest {

    private static final long WAIT_SECONDS = 5;
    private static final Duration LONG_DRAIN = Duration.ofSeconds(WAIT_SECONDS);
    private static final Duration SHORT_DRAIN = Duration.ofMillis(100);
    private static final String ORDER_GOAL = "process order";
    private static final String STUBBORN_GOAL = "stubborn job";
    private static final String QUICK_GOAL = "quick ping";

    private final CountDownLatch workStarted = new CountDownLatch(1);
    private final CountDownLatch workRelease = new CountDownLatch(1);
    private final CountDownLatch stubbornStarted = new CountDownLatch(1);
    private final CountDownLatch stubbornRelease = new CountDownLatch(1);
    private final AtomicInteger releaseCalls = new AtomicInteger();

    private JdbcExecutionStore store;
    private NexoraEngine engine;

    @BeforeEach
    void setUp() {
        store = JdbcExecutionStore.h2InMemory();
    }

    @AfterEach
    void tearDown() {
        workRelease.countDown();
        stubbornRelease.countDown();
        if (engine != null) {
            engine.shutdown(Duration.ZERO);
        }
        store.close();
    }

    @Test
    void shutdownWaitsForInFlightExecutionToFinish() throws Exception {
        engine = buildEngine(false);
        CompletableFuture<ExecutionResult> execution = engine.execute(new Intent(ORDER_GOAL, Map.of()));
        assertThat(workStarted.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<DrainResult> shutdown = CompletableFuture.supplyAsync(() -> engine.shutdown(LONG_DRAIN));
        awaitCondition(() -> !engine.isAcceptingExecutions());
        assertThat(shutdown).as("shutdown waits for the running execution").isNotDone();
        workRelease.countDown();

        DrainResult drain = shutdown.get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(execution.get(WAIT_SECONDS, TimeUnit.SECONDS).status()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(drain.inFlight()).isEqualTo(1);
        assertThat(drain.completed()).isEqualTo(1);
        assertThat(drain.cancelled()).isZero();
        assertThat(drain.clean()).isTrue();
    }

    @Test
    void newExecutionsAreRejectedAndReadinessIsDownWhileDraining() throws Exception {
        engine = buildEngine(false);
        engine.execute(new Intent(ORDER_GOAL, Map.of()));
        assertThat(workStarted.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<DrainResult> shutdown = CompletableFuture.supplyAsync(() -> engine.shutdown(LONG_DRAIN));
        awaitCondition(() -> !engine.isAcceptingExecutions());

        assertThatThrownBy(() -> engine.execute(new Intent(QUICK_GOAL, Map.of())))
                .isInstanceOf(EngineShuttingDownException.class)
                .hasMessageContaining("shutting down");
        assertThatThrownBy(() -> engine.submit(new Intent(QUICK_GOAL, Map.of())))
                .isInstanceOf(EngineShuttingDownException.class);
        NexoraEngine.ReadinessReport readiness = engine.readiness();
        assertThat(readiness.ready()).isFalse();
        assertThat(readiness.checks())
                .containsEntry(NexoraEngine.ReadinessReport.CHECK_EXECUTOR, NexoraEngine.HealthStatus.DOWN);

        workRelease.countDown();
        shutdown.get(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    @Test
    void replayIsRejectedAfterShutdown() throws Exception {
        engine = buildEngine(false);
        ExecutionHandle handle = engine.submit(new Intent(ORDER_GOAL, Map.of()));
        assertThat(workStarted.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(engine.cancel(handle.executionId()).get(WAIT_SECONDS, TimeUnit.SECONDS))
                .isTrue();
        handle.result().get(WAIT_SECONDS, TimeUnit.SECONDS);
        CompletableFuture<RuntimeException> replayFailure = new CompletableFuture<>();
        engine.subscribeSync(EngineShutdownEvent.class, e -> {
            // The store is still open here, so the replay gets as far as the admission check.
            try {
                engine.replay(handle.executionId());
                replayFailure.complete(null);
            } catch (RuntimeException ex) {
                replayFailure.complete(ex);
            }
        });

        engine.shutdown(LONG_DRAIN);

        assertThat(replayFailure.get(WAIT_SECONDS, TimeUnit.SECONDS)).isInstanceOf(EngineShuttingDownException.class);
    }

    @Test
    void executionPastDrainTimeoutIsCancelledAndCompensatedBeforeTheStoreCloses() throws Exception {
        engine = buildEngine(true);
        CompletableFuture<ExecutionState> stateAtShutdownEvent = new CompletableFuture<>();
        CompletableFuture<PlanCancelledEvent> cancelled = new CompletableFuture<>();
        engine.subscribe(PlanCancelledEvent.class, cancelled::complete);
        ExecutionHandle handle = engine.submit(new Intent(ORDER_GOAL, Map.of()));
        engine.subscribeSync(
                EngineShutdownEvent.class,
                e -> stateAtShutdownEvent.complete(store.findById(handle.executionId())
                        .map(ExecutionRecord::state)
                        .orElse(null)));
        assertThat(workStarted.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();

        DrainResult drain = engine.shutdown(SHORT_DRAIN);

        assertThat(handle.result().get(WAIT_SECONDS, TimeUnit.SECONDS).status()).isEqualTo(ExecutionStatus.CANCELLED);
        assertThat(drain.inFlight()).isEqualTo(1);
        assertThat(drain.completed()).isZero();
        assertThat(drain.cancelled()).isEqualTo(1);
        assertThat(drain.clean()).isTrue();
        assertThat(releaseCalls.get()).as("completed step compensated").isEqualTo(1);
        assertThat(stateAtShutdownEvent.get(WAIT_SECONDS, TimeUnit.SECONDS)).isEqualTo(ExecutionState.COMPENSATED);
        assertThat(cancelled.get(WAIT_SECONDS, TimeUnit.SECONDS).executionId()).isEqualTo(handle.executionId());
    }

    @Test
    void executionIgnoringCancelIsReportedUnfinished() throws Exception {
        engine = buildEngine(false);
        engine.execute(new Intent(STUBBORN_GOAL, Map.of()));
        assertThat(stubbornStarted.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();

        DrainResult drain = engine.shutdown(SHORT_DRAIN);

        assertThat(drain.inFlight()).isEqualTo(1);
        assertThat(drain.cancelled()).isEqualTo(1);
        assertThat(drain.unfinished()).isEqualTo(1);
        assertThat(drain.clean()).isFalse();
        assertThat(drain.elapsed()).isGreaterThanOrEqualTo(SHORT_DRAIN.multipliedBy(2));
    }

    @Test
    void shutdownEventCarriesDrainStats() throws Exception {
        engine = buildEngine(false);
        CompletableFuture<EngineShutdownEvent> event = new CompletableFuture<>();
        engine.subscribeSync(EngineShutdownEvent.class, event::complete);
        engine.execute(new Intent(QUICK_GOAL, Map.of())).get(WAIT_SECONDS, TimeUnit.SECONDS);

        DrainResult drain = engine.shutdown(LONG_DRAIN);

        EngineShutdownEvent published = event.get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(published.inFlight()).isEqualTo(drain.inFlight()).isZero();
        assertThat(published.completed()).isEqualTo(drain.completed());
        assertThat(published.cancelled()).isEqualTo(drain.cancelled());
        assertThat(published.unfinished()).isEqualTo(drain.unfinished());
        assertThat(published.elapsed()).isEqualTo(drain.elapsed());
        assertThat(published.occurredAt()).isNotNull();
    }

    @Test
    void repeatAndConcurrentShutdownsShareTheFirstResult() throws Exception {
        engine = buildEngine(false);
        AtomicInteger events = new AtomicInteger();
        engine.subscribeSync(EngineShutdownEvent.class, e -> events.incrementAndGet());
        engine.execute(new Intent(ORDER_GOAL, Map.of()));
        assertThat(workStarted.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<DrainResult> first = CompletableFuture.supplyAsync(() -> engine.shutdown(LONG_DRAIN));
        awaitCondition(() -> !engine.isAcceptingExecutions());
        CompletableFuture<DrainResult> second = CompletableFuture.supplyAsync(() -> engine.shutdown(Duration.ZERO));
        workRelease.countDown();

        assertThat(second.get(WAIT_SECONDS, TimeUnit.SECONDS)).isEqualTo(first.get(WAIT_SECONDS, TimeUnit.SECONDS));
        assertThat(engine.shutdown(Duration.ZERO)).isEqualTo(first.get());
        engine.close();
        assertThat(events.get()).as("event published once").isEqualTo(1);
    }

    @Test
    void shutdownHookIsRegisteredByDefaultAndRemovedOnShutdown() {
        engine = buildEngine(false);
        assertThat(engine.shutdownHookRegistered()).isTrue();

        engine.shutdown(Duration.ZERO);

        assertThat(engine.shutdownHookRegistered()).isFalse();
    }

    @Test
    void shutdownHookStaysRegisteredWhileDraining() throws Exception {
        engine = buildEngine(false);
        engine.execute(new Intent(ORDER_GOAL, Map.of()));
        assertThat(workStarted.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<DrainResult> shutdown = CompletableFuture.supplyAsync(() -> engine.shutdown(LONG_DRAIN));
        awaitCondition(() -> !engine.isAcceptingExecutions());

        assertThat(engine.shutdownHookRegistered())
                .as("a SIGTERM during an explicit drain must still find the hook")
                .isTrue();
        workRelease.countDown();
        shutdown.get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(engine.shutdownHookRegistered()).isFalse();
    }

    // Two id pairs because the plugin manager's iteration order depends on the ids: one of them
    // reaches the required plugin before its dependent, which a single pass cannot release.
    @Test
    void pluginsRequiredByOthersAreReleasedAfterTheirDependents() {
        assertAllPluginsReleased("a-base", "z-addon");
    }

    @Test
    void pluginsRequiredByOthersAreReleasedAfterTheirDependentsWithIdsReversed() {
        assertAllPluginsReleased("z-base", "a-addon");
    }

    private void assertAllPluginsReleased(String baseId, String addonId) {
        List<String> shutDown = new java.util.concurrent.CopyOnWriteArrayList<>();
        engine = NexoraEngine.builder()
                .withExecutionStore(store)
                .withPlugin(namedPlugin(baseId, List.of(), shutDown))
                .withPlugin(namedPlugin(addonId, List.of(baseId), shutDown))
                .build();

        engine.shutdown(Duration.ZERO);

        assertThat(shutDown).containsExactly(addonId, baseId);
        assertThat(engine.activePluginIds()).isEmpty();
        assertThat(store.isHealthy()).as("store closed after plugins").isFalse();
    }

    private static NexoraPlugin namedPlugin(String id, List<String> requires, List<String> shutDown) {
        return new NexoraPlugin() {
            @Override
            public PluginDescriptor descriptor() {
                return new PluginDescriptor(id, "1.0", id, requires, null);
            }

            @Override
            public void initialize(PluginContext ctx) {}

            @Override
            public List<CapabilityProvider> capabilityProviders() {
                return List.of();
            }

            @Override
            public void shutdown() {
                shutDown.add(id);
            }
        };
    }

    @Test
    void shutdownHookCanBeDisabled() {
        engine = builder(false).withShutdownHook(false).build();

        assertThat(engine.shutdownHookRegistered()).isFalse();
    }

    @Test
    void negativeDrainTimeoutIsRejected() {
        engine = buildEngine(false);

        assertThatThrownBy(() -> engine.shutdown(Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("drainTimeout");
        assertThat(engine.isAcceptingExecutions())
                .as("a rejected call does not start shutdown")
                .isTrue();
        assertThatThrownBy(() -> NexoraEngine.builder().withShutdownDrainTimeout(Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NexoraEngine.builder().withShutdownDrainTimeout(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void closeUsesTheConfiguredDrainTimeout() throws Exception {
        engine = builder(false).withShutdownDrainTimeout(SHORT_DRAIN).build();
        CompletableFuture<ExecutionResult> execution = engine.execute(new Intent(ORDER_GOAL, Map.of()));
        assertThat(workStarted.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();

        engine.close();

        assertThat(execution.get(WAIT_SECONDS, TimeUnit.SECONDS).status()).isEqualTo(ExecutionStatus.CANCELLED);
    }

    private static void awaitCondition(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within " + WAIT_SECONDS + "s");
            }
            Thread.sleep(10);
        }
    }

    private NexoraEngine buildEngine(boolean sagaEnabled) {
        return builder(sagaEnabled).build();
    }

    private NexoraEngine.Builder builder(boolean sagaEnabled) {
        return NexoraEngine.builder()
                .withExecutionStore(store)
                .withSagaEnabled(sagaEnabled)
                .withPlugin(testPlugin())
                .withStepDefinition(StepDefinition.builder("reserve", "reserve")
                        .withMatcher(goal -> goal.contains("order"))
                        .withCompensateCapabilityId("release")
                        .build())
                .withStepDefinition(StepDefinition.builder("work", "work")
                        .withMatcher(goal -> goal.contains("order"))
                        .dependsOn("reserve")
                        .build())
                .withStepDefinition(StepDefinition.builder("stubborn", "stubborn")
                        .withMatcher(goal -> goal.contains("stubborn"))
                        .build())
                .withStepDefinition(StepDefinition.builder("ping", "ping")
                        .withMatcher(goal -> goal.contains("quick"))
                        .build());
    }

    private NexoraPlugin testPlugin() {
        return new NexoraPlugin() {
            @Override
            public PluginDescriptor descriptor() {
                return new PluginDescriptor("shutdown-test", "1.0", "graceful shutdown test plugin", List.of(), null);
            }

            @Override
            public void initialize(PluginContext ctx) {}

            @Override
            public List<CapabilityProvider> capabilityProviders() {
                return List.of(
                        provider("reserve", req -> CapabilityResult.success("reserved")),
                        provider("release", req -> {
                            releaseCalls.incrementAndGet();
                            return CapabilityResult.success("released");
                        }),
                        provider("work", req -> awaitRelease()),
                        provider("stubborn", req -> ignoreInterrupts()),
                        provider("ping", req -> CapabilityResult.success("pong")));
            }

            @Override
            public void shutdown() {}
        };
    }

    private CapabilityResult awaitRelease() {
        workStarted.countDown();
        try {
            return workRelease.await(WAIT_SECONDS, TimeUnit.SECONDS)
                    ? CapabilityResult.success("done")
                    : CapabilityResult.failure("NOT_RELEASED", "test never released the step");
        } catch (InterruptedException e) {
            return CapabilityResult.failure("INTERRUPTED", "interrupted by cancel");
        }
    }

    /** A step that swallows interrupts, as badly behaved blocking client code does, until the test lets it go. */
    private CapabilityResult ignoreInterrupts() {
        stubbornStarted.countDown();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (System.nanoTime() < deadline) {
            try {
                if (stubbornRelease.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
                    break;
                }
            } catch (InterruptedException e) {
                // Deliberately ignored: this step simulates code that does not honour cancellation.
            }
        }
        return CapabilityResult.success("finally done");
    }

    private static CapabilityProvider provider(String id, Capability capability) {
        return new CapabilityProvider() {
            @Override
            public CapabilityDescriptor descriptor() {
                return new CapabilityDescriptor(id, id, List.of(), List.of(), true, false);
            }

            @Override
            public Capability create(PluginContext ctx) {
                return capability;
            }
        };
    }
}
