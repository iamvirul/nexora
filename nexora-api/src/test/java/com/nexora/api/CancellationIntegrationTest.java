package com.nexora.api;

import com.nexora.api.observability.NexoraObservability;
import com.nexora.core.capability.CapabilityResult;
import com.nexora.core.execution.ExecutionHandle;
import com.nexora.core.execution.ExecutionResult;
import com.nexora.core.execution.ExecutionStatus;
import com.nexora.core.intent.Intent;
import com.nexora.event.CompensationCompletedEvent;
import com.nexora.event.PlanCancelledEvent;
import com.nexora.event.PlanStartedEvent;
import com.nexora.persistence.DeadLetterReviewState;
import com.nexora.persistence.ExecutionRecord;
import com.nexora.persistence.ExecutionState;
import com.nexora.persistence.jdbc.JdbcExecutionStore;
import com.nexora.planner.model.StepDefinition;
import com.nexora.spi.Capability;
import com.nexora.spi.CapabilityDescriptor;
import com.nexora.spi.CapabilityProvider;
import com.nexora.spi.NexoraPlugin;
import com.nexora.spi.PluginContext;
import com.nexora.spi.PluginDescriptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CancellationIntegrationTest {

    private static final long WAIT_SECONDS = 5;
    private static final String ORDER_GOAL = "process order";
    private static final String QUICK_GOAL = "quick notify";

    private final CountDownLatch blockStarted = new CountDownLatch(1);
    private final CountDownLatch blockInterrupted = new CountDownLatch(1);
    // Held closed to keep an interrupted step "draining"; open by default so cancels finish promptly.
    private CountDownLatch drainGate = new CountDownLatch(0);
    private final AtomicInteger notifyCalls = new AtomicInteger();
    private final AtomicInteger releaseCalls = new AtomicInteger();

    private JdbcExecutionStore store;
    private NexoraEngine engine;
    private final CompletableFuture<String> startedExecutionId = new CompletableFuture<>();

    @BeforeEach
    void setUp() {
        store = JdbcExecutionStore.h2InMemory();
    }

    @AfterEach
    void tearDown() {
        drainGate.countDown();
        if (engine != null) {
            engine.close();
        }
        store.close();
    }

    @Test
    void cancellingRunningExecutionStopsItAndRecordsCancelled() throws Exception {
        engine = buildEngine(false);
        CompletableFuture<PlanCancelledEvent> cancelledEvent = new CompletableFuture<>();
        engine.subscribe(PlanCancelledEvent.class, cancelledEvent::complete);

        CompletableFuture<ExecutionResult> execution = engine.execute(new Intent(ORDER_GOAL, Map.of()));
        String executionId = awaitBlockingStep();

        assertThat(engine.cancel(executionId).get(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        ExecutionResult result = execution.get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertThat(result.status()).isEqualTo(ExecutionStatus.CANCELLED);
        assertThat(blockInterrupted.getCount()).as("running step interrupted").isZero();
        assertThat(notifyCalls.get()).as("dependent step never started").isZero();
        assertThat(store.findById(executionId)).map(ExecutionRecord::state).contains(ExecutionState.CANCELLED);
        assertThat(cancelledEvent.get(WAIT_SECONDS, TimeUnit.SECONDS).executionId()).isEqualTo(executionId);
        assertThat(store.findDeadLetters(DeadLetterReviewState.PENDING, 0, 10))
                .as("a deliberate cancel is not a dead letter").isEmpty();
    }

    @Test
    void submitExposesExecutionIdBeforeTheExecutionFinishes() throws Exception {
        engine = buildEngine(false);

        ExecutionHandle handle = engine.submit(new Intent(ORDER_GOAL, Map.of()));
        String startedId = awaitBlockingStep();

        assertThat(handle.result()).isNotDone();
        assertThat(handle.executionId()).isEqualTo(startedId);
        assertThat(engine.cancel(handle.executionId()).get(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        ExecutionResult result = handle.result().get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(result.executionId()).isEqualTo(handle.executionId());
        assertThat(result.status()).isEqualTo(ExecutionStatus.CANCELLED);
    }

    @Test
    void cancellationCompensatesCompletedStepsWhenSagaIsEnabled() throws Exception {
        engine = buildEngine(true);
        CountDownLatch compensated = new CountDownLatch(1);
        engine.subscribe(CompensationCompletedEvent.class, e -> compensated.countDown());

        CompletableFuture<ExecutionResult> execution = engine.execute(new Intent(ORDER_GOAL, Map.of()));
        String executionId = awaitBlockingStep();
        engine.cancel(executionId).get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertThat(execution.get(WAIT_SECONDS, TimeUnit.SECONDS).status()).isEqualTo(ExecutionStatus.CANCELLED);
        assertThat(compensated.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(releaseCalls.get()).isEqualTo(1);
        awaitCondition(() -> store.findById(executionId)
                .map(r -> r.state() == ExecutionState.COMPENSATED).orElse(false));
    }

    @Test
    void repeatCancelWhileDrainingIsIdempotent() throws Exception {
        drainGate = new CountDownLatch(1);
        engine = buildEngine(false);

        CompletableFuture<ExecutionResult> execution = engine.execute(new Intent(ORDER_GOAL, Map.of()));
        String executionId = awaitBlockingStep();

        assertThat(engine.cancel(executionId).get(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(blockInterrupted.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(engine.cancel(executionId).get(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();

        drainGate.countDown();
        assertThat(execution.get(WAIT_SECONDS, TimeUnit.SECONDS).status()).isEqualTo(ExecutionStatus.CANCELLED);
    }

    @Test
    void cancellingFinishedExecutionReturnsFalse() throws Exception {
        engine = buildEngine(false);
        ExecutionResult result = engine.execute(new Intent(QUICK_GOAL, Map.of())).get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(result.status()).isEqualTo(ExecutionStatus.COMPLETED);

        assertThat(engine.cancel(result.executionId()).get(WAIT_SECONDS, TimeUnit.SECONDS)).isFalse();
        assertThat(store.findById(result.executionId())).map(ExecutionRecord::state).contains(ExecutionState.COMPLETED);
    }

    @Test
    void cancellingUnknownExecutionFailsWithNotFound() {
        engine = buildEngine(false);

        assertThatThrownBy(() -> engine.cancel("does-not-exist").get(WAIT_SECONDS, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .cause()
                .isInstanceOf(ExecutionNotFoundException.class)
                .hasMessageContaining("does-not-exist");
    }

    @Test
    void cancellingExecutionOwnedByAnotherEngineFailsWithNotOwned() {
        engine = buildEngine(false);
        String orphanId = UUID.randomUUID().toString();
        store.createExecution(ExecutionRecord.started(orphanId, "trace", ORDER_GOAL, Map.of(), Instant.now()));

        assertThatThrownBy(() -> engine.cancel(orphanId).get(WAIT_SECONDS, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .cause()
                .isInstanceOf(ExecutionNotOwnedException.class);
    }

    @Test
    void cancelRejectsBlankExecutionId() {
        engine = buildEngine(false);

        assertThatThrownBy(() -> engine.cancel(" ").join())
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cancelledExecutionIsCountedAndLeavesNoActiveExecution() throws Exception {
        engine = buildEngine(false);
        try (NexoraObservability observability = NexoraObservability.attach(engine)) {
            CompletableFuture<ExecutionResult> execution = engine.execute(new Intent(ORDER_GOAL, Map.of()));
            engine.cancel(awaitBlockingStep()).get(WAIT_SECONDS, TimeUnit.SECONDS);
            execution.get(WAIT_SECONDS, TimeUnit.SECONDS);

            // Metrics update on the async event bus; wait for the whole handler, not just its first line.
            awaitCondition(() -> {
                String scrape = observability.scrapePrometheus();
                return scrape.contains("nexora_plan_cancelled_total 1.0")
                        && scrape.contains("nexora_active_executions 0.0");
            });
            assertThat(observability.scrapePrometheus()).containsPattern("(?m)^nexora_plan_failed_total 0\\.0$");
        }
    }

    private String awaitBlockingStep() throws Exception {
        assertThat(blockStarted.await(WAIT_SECONDS, TimeUnit.SECONDS)).as("blocking step started").isTrue();
        return startedExecutionId.get(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    private static void awaitCondition(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within " + WAIT_SECONDS + "s");
            }
            Thread.sleep(20);
        }
    }

    private NexoraEngine buildEngine(boolean sagaEnabled) {
        NexoraEngine built = NexoraEngine.builder()
                .withExecutionStore(store)
                .withSagaEnabled(sagaEnabled)
                .withPlugin(testPlugin())
                .withStepDefinition(StepDefinition.builder("reserve", "reserve")
                        .withMatcher(goal -> goal.contains("order"))
                        .withCompensateCapabilityId("release")
                        .build())
                .withStepDefinition(StepDefinition.builder("block", "block")
                        .withMatcher(goal -> goal.contains("order"))
                        .dependsOn("reserve")
                        .build())
                .withStepDefinition(StepDefinition.builder("notify", "notify")
                        .withMatcher(goal -> goal.contains("order"))
                        .dependsOn("block")
                        .build())
                .withStepDefinition(StepDefinition.builder("quick_notify", "notify")
                        .withMatcher(goal -> goal.contains("quick"))
                        .build())
                .build();
        built.subscribe(PlanStartedEvent.class, e -> startedExecutionId.complete(e.executionId()));
        return built;
    }

    private NexoraPlugin testPlugin() {
        return new NexoraPlugin() {
            @Override public PluginDescriptor descriptor() {
                return new PluginDescriptor("cancel-test", "1.0", "cancellation test plugin", List.of(), null);
            }
            @Override public void initialize(PluginContext ctx) {}
            @Override public List<CapabilityProvider> capabilityProviders() {
                return List.of(
                        provider("reserve", req -> CapabilityResult.success("reserved")),
                        provider("release", req -> {
                            releaseCalls.incrementAndGet();
                            return CapabilityResult.success("released");
                        }),
                        provider("block", req -> blockUntilInterrupted()),
                        provider("notify", req -> {
                            notifyCalls.incrementAndGet();
                            return CapabilityResult.success("notified");
                        }));
            }
            @Override public void shutdown() {}
        };
    }

    private CapabilityResult blockUntilInterrupted() {
        blockStarted.countDown();
        try {
            new CountDownLatch(1).await();
            return CapabilityResult.success("unreachable");
        } catch (InterruptedException e) {
            blockInterrupted.countDown();
            awaitDrainGate();
            return CapabilityResult.failure("INTERRUPTED", "interrupted by cancel");
        }
    }

    private void awaitDrainGate() {
        try {
            drainGate.await(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static CapabilityProvider provider(String id, Capability capability) {
        return new CapabilityProvider() {
            @Override public CapabilityDescriptor descriptor() {
                return new CapabilityDescriptor(id, id, List.of(), List.of(), true, false);
            }
            @Override public Capability create(PluginContext ctx) {
                return capability;
            }
        };
    }
}
