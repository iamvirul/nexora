package com.nexora.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexora.api.observability.NexoraObservability;
import com.nexora.core.capability.CapabilityResult;
import com.nexora.core.execution.ExecutionHandle;
import com.nexora.core.execution.ExecutionResult;
import com.nexora.core.execution.ExecutionStatus;
import com.nexora.core.intent.Intent;
import com.nexora.core.tenant.TenantId;
import com.nexora.persistence.DeadLetterRecord;
import com.nexora.persistence.ExecutionFilter;
import com.nexora.persistence.ExecutionRecord;
import com.nexora.persistence.ExecutionState;
import com.nexora.persistence.ExecutionStore;
import com.nexora.persistence.MissedFirePolicy;
import com.nexora.persistence.ScheduleRecord;
import com.nexora.persistence.jdbc.JdbcExecutionStore;
import com.nexora.planner.model.StepDefinition;
import com.nexora.runtime.scheduler.ScheduledExecution;
import com.nexora.spi.Capability;
import com.nexora.spi.CapabilityDescriptor;
import com.nexora.spi.CapabilityProvider;
import com.nexora.spi.NexoraPlugin;
import com.nexora.spi.PluginContext;
import com.nexora.spi.PluginDescriptor;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tenant isolation through the public engine API, against a real (in-memory H2) store. */
class TenantIsolationIntegrationTest {

    private static final long WAIT_SECONDS = 5;
    private static final String ACME = "acme";
    private static final String GLOBEX = "globex";
    // Long enough for a second, wrongly claimed catch-up execution to have run and been recorded.
    private static final long CATCH_UP_SETTLE_MILLIS = 500;
    // Yearly, so the schedule's next legitimate firing never lands inside the counting window.
    private static final String YEARLY_CRON = "0 0 1 1 *";

    // executionId -> tenant the capability saw in its request
    private final Map<String, String> tenantSeen = new ConcurrentHashMap<>();
    private final AtomicInteger failuresLeft = new AtomicInteger();
    private volatile CountDownLatch blockStarted = new CountDownLatch(1);
    private volatile CountDownLatch blockRelease = new CountDownLatch(1);

    private JdbcExecutionStore store;
    private NexoraEngine engine;
    private NexoraEngine acmeEngine;

    @BeforeEach
    void setUp() {
        store = JdbcExecutionStore.h2InMemory();
        engine = builder().build();
    }

    @AfterEach
    void tearDown() {
        blockRelease.countDown();
        if (acmeEngine != null) {
            acmeEngine.close();
        }
        engine.close();
    }

    @Test
    void capabilityReceivesTheTenantOfItsExecution() throws Exception {
        ExecutionResult result = run(engine, intent("whoami", ACME));

        assertThat(result.status()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(tenantSeen.get(result.executionId())).isEqualTo(ACME);
        assertThat(store.findById(result.executionId()).orElseThrow().tenantId())
                .isEqualTo(ACME);
    }

    @Test
    void intentWithoutTenantRunsForTheDefaultTenantAsBefore() throws Exception {
        ExecutionResult result = run(engine, new Intent("whoami", Map.of()));

        assertThat(tenantSeen.get(result.executionId())).isEqualTo(TenantId.DEFAULT);
        assertThat(store.findById(result.executionId()).orElseThrow().tenantId())
                .isEqualTo(TenantId.DEFAULT);
        assertThat(engine.tenantScope()).isEmpty();
    }

    @Test
    void historyOfOneTenantNeverContainsAnotherTenantsExecutions() throws Exception {
        String acmeId = run(engine, intent("whoami", ACME)).executionId();
        String globexId = run(engine, intent("whoami", GLOBEX)).executionId();
        awaitTerminal(acmeId);
        awaitTerminal(globexId);

        List<String> acmeHistory = store.findByFilter(ExecutionFilter.all().forTenant(ACME)).stream()
                .map(ExecutionRecord::executionId)
                .toList();

        assertThat(acmeHistory).containsExactly(acmeId);
        assertThat(store.findById(GLOBEX, acmeId)).isEmpty();
    }

    @Test
    void deadLetterKeepsTheTenantAndItsReplayRunsForThatTenant() throws Exception {
        failuresLeft.set(1);
        String failedId = run(engine, intent("flaky", GLOBEX)).executionId();
        awaitCondition(() -> !store.findDeadLetters(GLOBEX, null, 0, 10).isEmpty());
        DeadLetterRecord deadLetter = store.findDeadLetters(GLOBEX, null, 0, 10).get(0);

        awaitTerminal(failedId);
        ExecutionHandle replay = awaitReplay(GLOBEX, deadLetter.executionId());
        replay.result().get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertThat(deadLetter.tenantId()).isEqualTo(GLOBEX);
        assertThat(deadLetter.executionId()).isEqualTo(failedId);
        assertThat(store.findDeadLetters(ACME, null, 0, 10)).isEmpty();
        assertThat(tenantSeen.get(replay.executionId())).isEqualTo(GLOBEX);
    }

    @Test
    void replayWithinAnotherTenantIsNotFoundAndReplayKeepsTheOriginalTenant() throws Exception {
        failuresLeft.set(1);
        String failedId = run(engine, intent("flaky", GLOBEX)).executionId();
        awaitTerminal(failedId);

        assertThatThrownBy(() -> engine.replay(ACME, failedId)).isInstanceOf(ExecutionNotFoundException.class);
        ExecutionHandle replay = awaitReplay(GLOBEX, failedId);

        assertThat(replay.result().get(WAIT_SECONDS, TimeUnit.SECONDS).status()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(awaitTerminal(replay.executionId()).tenantId()).isEqualTo(GLOBEX);
    }

    @Test
    void cancelOfAnotherTenantsRunningExecutionIsNotFound() throws Exception {
        ExecutionHandle running = engine.submit(intent("block", ACME));
        assertThat(blockStarted.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> engine.cancel(GLOBEX, running.executionId()).get(WAIT_SECONDS, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(ExecutionNotFoundException.class);
        assertThat(engine.cancel(ACME, running.executionId()).get(WAIT_SECONDS, TimeUnit.SECONDS))
                .isTrue();
        assertThat(running.result().get(WAIT_SECONDS, TimeUnit.SECONDS).status())
                .isEqualTo(ExecutionStatus.CANCELLED);
    }

    @Test
    void capabilityRestrictedToOneTenantIsHiddenFromAndRefusedToOthers() throws Exception {
        assertThat(run(engine, intent("acme report", GLOBEX)).status()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(run(engine, intent("acme report", ACME)).status()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(ids(engine.listCapabilities(GLOBEX))).doesNotContain("acme-report");
        assertThat(ids(engine.listCapabilities(ACME))).contains("acme-report", "whoami");
        assertThat(ids(engine.listCapabilities())).contains("acme-report");
    }

    @Test
    void tenantBoundEngineGivesIntentsItsTenantAndRefusesOtherTenants() throws Exception {
        acmeEngine = builder().withTenantId(ACME).build();

        ExecutionResult result = run(acmeEngine, new Intent("whoami", Map.of()));

        assertThat(acmeEngine.tenantScope()).contains(ACME);
        assertThat(tenantSeen.get(result.executionId())).isEqualTo(ACME);
        assertThatThrownBy(() -> acmeEngine.submit(intent("whoami", GLOBEX)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(ids(acmeEngine.listCapabilities())).contains("acme-report");
    }

    @Test
    void tenantBoundEngineCannotReplayOrCancelAnotherTenantsWork() throws Exception {
        failuresLeft.set(1);
        String globexFailed = run(engine, intent("flaky", GLOBEX)).executionId();
        awaitTerminal(globexFailed);
        acmeEngine = builder().withTenantId(ACME).build();

        assertThatThrownBy(() -> acmeEngine.replay(globexFailed)).isInstanceOf(ExecutionNotFoundException.class);
        assertThatThrownBy(() -> acmeEngine.replay(GLOBEX, globexFailed))
                .isInstanceOf(ExecutionNotFoundException.class);
        assertThatThrownBy(() -> acmeEngine.cancel(globexFailed).get(WAIT_SECONDS, TimeUnit.SECONDS))
                .hasCauseInstanceOf(ExecutionNotFoundException.class);
    }

    @Test
    void tenantBoundEngineTreatsAnotherTenantsDeadLetterAsNotFoundAndRunsNothing() {
        acmeEngine = builder().withTenantId(ACME).build();
        // Original execution records gone, so only the dead letter's own goal and context remain.
        DeadLetterRecord defaultTenant = DeadLetterRecord.pending(
                "dl-default", "gone-1", "whoami", Map.of(), "X", "x", Instant.now(), TenantId.DEFAULT);
        DeadLetterRecord globex =
                DeadLetterRecord.pending("dl-globex", "gone-2", "whoami", Map.of(), "X", "x", Instant.now(), GLOBEX);

        assertThatThrownBy(() -> acmeEngine.replayDeadLetter(defaultTenant))
                .isInstanceOf(ExecutionNotFoundException.class);
        assertThatThrownBy(() -> acmeEngine.replayDeadLetter(globex)).isInstanceOf(ExecutionNotFoundException.class);
        assertThat(tenantSeen).isEmpty();
    }

    @Test
    void schedulesAreListedAndCancelledWithinTheirTenant() {
        ScheduledExecution acmeSchedule = engine.schedule("0 0 * * *", intent("whoami", ACME));
        ScheduledExecution globexSchedule = engine.schedule("0 0 * * *", intent("whoami", GLOBEX));

        assertThat(engine.listSchedules(ACME)).extracting("id").containsExactly(acmeSchedule.id());
        assertThat(engine.cancelSchedule(GLOBEX, acmeSchedule.id())).isFalse();
        assertThat(engine.listActiveSchedules()).hasSize(2);
        assertThat(engine.cancelSchedule(ACME, acmeSchedule.id())).isTrue();
        assertThat(engine.listActiveSchedules()).extracting("id").containsExactly(globexSchedule.id());
    }

    @Test
    void tenantBoundEngineOnlySeesAndCancelsItsOwnSchedules() {
        ScheduledExecution globexSchedule = engine.schedule("0 0 * * *", intent("whoami", GLOBEX));
        acmeEngine = builder().withTenantId(ACME).build();
        ScheduledExecution acmeSchedule = acmeEngine.schedule("0 0 * * *", new Intent("whoami", Map.of()));

        acmeEngine.cancelSchedule(globexSchedule.id());

        assertThat(acmeEngine.listSchedules()).extracting("id").containsExactly(acmeSchedule.id());
        assertThat(acmeEngine.listSchedules().get(0).tenantId()).isEqualTo(ACME);
        assertThat(store.findScheduleById(globexSchedule.id()).orElseThrow().active())
                .isTrue();
    }

    @Test
    void executionMetricsAreLabelledWithTheTenant() throws Exception {
        try (NexoraObservability observability = NexoraObservability.attach(engine)) {
            run(engine, intent("whoami", ACME));
            run(engine, intent("whoami", ACME));
            run(engine, intent("whoami", GLOBEX));

            awaitCondition(() -> {
                String scrape = observability.scrapePrometheus();
                return scrape.contains("nexora_plan_completed_total{tenant=\"acme\",} 2.0")
                        && scrape.contains("nexora_plan_completed_total{tenant=\"globex\",} 1.0");
            });
            String scrape = observability.scrapePrometheus();

            assertThat(scrape)
                    .contains("nexora_step_completed_total{capability_id=\"whoami\",tenant=\"acme\",} 2.0")
                    .contains("nexora_active_executions{tenant=\"acme\",} 0.0")
                    .contains("nexora_active_executions{tenant=\"globex\",} 0.0");
        }
    }

    @Test
    void overdueScheduleFiresOnceWhenBoundAndUnboundEnginesShareTheStore() throws Exception {
        Instant now = Instant.now();
        store.createSchedule(new ScheduleRecord(
                "overdue",
                YEARLY_CRON,
                "whoami",
                Map.of(),
                MissedFirePolicy.FIRE_ONCE,
                now.minus(Duration.ofDays(800)),
                null,
                now.minus(Duration.ofDays(365)),
                null,
                true,
                ACME));
        // Both engines see the schedule as overdue, as when they start together.
        FlakyExecutionStore shared = new FlakyExecutionStore(store);
        shared.freezeActiveSchedules();

        acmeEngine = builder(shared).withTenantId(ACME).build();
        try (NexoraEngine unbound = builder(shared).build()) {
            awaitCondition(() -> !tenantSeen.isEmpty());
            Thread.sleep(CATCH_UP_SETTLE_MILLIS);

            assertThat(tenantSeen).hasSize(1);
            assertThat(tenantSeen.values()).containsExactly(ACME);
            assertThat(unbound.listSchedules()).hasSize(1);
        }
    }

    @Test
    void invalidTenantIsRejectedBeforeAnythingRuns() {
        assertThatThrownBy(() -> builder().withTenantId("Not A Tenant")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> engine.replay("Bad Tenant", "x")).isInstanceOf(IllegalArgumentException.class);
    }

    private NexoraEngine.Builder builder() {
        return builder(store);
    }

    private NexoraEngine.Builder builder(ExecutionStore executionStore) {
        return NexoraEngine.builder()
                .withExecutionStore(executionStore)
                .withPlugin(plugin())
                .withStepDefinition(new StepDefinition("whoami", "whoami", "whoami"::equals))
                .withStepDefinition(new StepDefinition("flaky", "flaky", "flaky"::equals))
                .withStepDefinition(new StepDefinition("block", "block", "block"::equals))
                .withStepDefinition(new StepDefinition("report", "acme-report", "acme report"::equals));
    }

    private static Intent intent(String goal, String tenantId) {
        return new Intent(goal, Map.of(), null, null, null, tenantId);
    }

    private static ExecutionResult run(NexoraEngine target, Intent intent) throws Exception {
        return target.execute(intent).get(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    private static List<String> ids(List<CapabilityDescriptor> descriptors) {
        return descriptors.stream().map(CapabilityDescriptor::id).toList();
    }

    private ExecutionRecord awaitTerminal(String executionId) throws InterruptedException {
        awaitCondition(() -> store.findById(executionId)
                .map(r -> r.state() != ExecutionState.RUNNING)
                .orElse(false));
        return store.findById(executionId).orElseThrow();
    }

    /** The engine still tracks a run for a moment after its final state is stored; replay waits that out. */
    private ExecutionHandle awaitReplay(String tenantId, String executionId) throws InterruptedException {
        AtomicReference<ExecutionHandle> replay = new AtomicReference<>();
        awaitCondition(() -> {
            try {
                replay.set(engine.replay(tenantId, executionId));
                return true;
            } catch (ExecutionNotReplayableException stillRunning) {
                return false;
            }
        });
        return replay.get();
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

    private NexoraPlugin plugin() {
        return new NexoraPlugin() {
            @Override
            public PluginDescriptor descriptor() {
                return new PluginDescriptor("tenant-test", "1.0", "test", List.of(), null);
            }

            @Override
            public void initialize(PluginContext ctx) {}

            @Override
            public void shutdown() {}

            @Override
            public List<CapabilityProvider> capabilityProviders() {
                return List.of(
                        provider(capability("whoami"), request -> {
                            tenantSeen.put(request.executionId(), request.tenantId());
                            return CapabilityResult.success(request.tenantId());
                        }),
                        provider(capability("flaky"), request -> {
                            tenantSeen.put(request.executionId(), request.tenantId());
                            return failuresLeft.getAndUpdate(n -> Math.max(0, n - 1)) > 0
                                    ? CapabilityResult.failure("FLAKY", "failing on purpose")
                                    : CapabilityResult.success("ok");
                        }),
                        provider(capability("block"), request -> {
                            blockStarted.countDown();
                            try {
                                blockRelease.await(WAIT_SECONDS, TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            return CapabilityResult.success("released");
                        }),
                        provider(
                                capability("acme-report").withTenantIds(Set.of(ACME)),
                                request -> CapabilityResult.success("report")));
            }
        };
    }

    private static CapabilityDescriptor capability(String id) {
        return new CapabilityDescriptor(id, id, List.of(), List.of(), true, false);
    }

    private static CapabilityProvider provider(CapabilityDescriptor descriptor, Capability capability) {
        return new CapabilityProvider() {
            @Override
            public CapabilityDescriptor descriptor() {
                return descriptor;
            }

            @Override
            public Capability create(PluginContext ctx) {
                return capability;
            }
        };
    }
}
