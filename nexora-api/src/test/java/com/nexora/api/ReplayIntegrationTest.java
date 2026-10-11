package com.nexora.api;

import com.nexora.core.capability.CapabilityResult;
import com.nexora.core.execution.ExecutionHandle;
import com.nexora.core.execution.ExecutionResult;
import com.nexora.core.execution.ExecutionStatus;
import com.nexora.core.intent.Intent;
import com.nexora.event.CompensationCompletedEvent;
import com.nexora.persistence.DeadLetterRecord;
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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReplayIntegrationTest {

    private static final long WAIT_SECONDS = 5;
    private static final String GOAL = "process order";

    // executionId -> (stepId -> idempotency key the capability received)
    private final Map<String, Map<String, String>> keysSeen = new ConcurrentHashMap<>();
    private final AtomicInteger chargeCalls = new AtomicInteger();
    private final AtomicInteger failChargeTimes = new AtomicInteger(1);

    private JdbcExecutionStore store;
    private NexoraEngine engine;

    @BeforeEach
    void setUp() {
        store = JdbcExecutionStore.h2InMemory();
    }

    @AfterEach
    void tearDown() {
        if (engine != null) {
            engine.close();
        }
        store.close();
    }

    @Test
    void replayOfFailedExecutionRunsAgainWithNewIdAndReplayOfMarker() throws Exception {
        engine = buildEngine(false);
        ExecutionResult original = run();
        assertThat(original.status()).isEqualTo(ExecutionStatus.FAILED);

        ExecutionHandle replay = engine.replay(original.executionId());
        ExecutionResult result = replay.result().get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertThat(replay.executionId()).isNotEqualTo(original.executionId());
        assertThat(result.status()).isEqualTo(ExecutionStatus.COMPLETED);
        ExecutionRecord stored = awaitStored(replay.executionId());
        assertThat(stored.context())
                .containsEntry(NexoraEngine.REPLAY_OF_CONTEXT_KEY, original.executionId())
                .containsEntry("orderId", "ORD-1");
        assertThat(stored.traceId()).isNotEqualTo(store.findById(original.executionId()).orElseThrow().traceId());
    }

    @Test
    void replayReusesKeysOfCompletedStepsAndGivesFailedStepsFreshKeys() throws Exception {
        engine = buildEngine(false);
        ExecutionResult original = run();

        ExecutionHandle replay = engine.replay(original.executionId());
        replay.result().get(WAIT_SECONDS, TimeUnit.SECONDS);

        Map<String, String> before = keysSeen.get(original.executionId());
        Map<String, String> after = keysSeen.get(replay.executionId());
        assertThat(after.get("reserve")).as("completed step keeps its key").isEqualTo(before.get("reserve"));
        assertThat(after.get("charge")).as("failed step gets a fresh key").isNotEqualTo(before.get("charge"));
    }

    @Test
    void replayOfCompensatedExecutionUsesFreshKeysEverywhere() throws Exception {
        engine = buildEngine(true);
        CountDownLatch compensated = new CountDownLatch(1);
        engine.subscribe(CompensationCompletedEvent.class, e -> compensated.countDown());
        ExecutionResult original = run();
        assertThat(compensated.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        awaitState(original.executionId(), ExecutionState.COMPENSATED);

        ExecutionHandle replay = engine.replay(original.executionId());
        replay.result().get(WAIT_SECONDS, TimeUnit.SECONDS);

        // reserve was rolled back by compensation, so it must really run again under a new key.
        assertThat(keysSeen.get(replay.executionId()).get("reserve"))
                .isNotEqualTo(keysSeen.get(original.executionId()).get("reserve"));
    }

    @Test
    void deadLetterReplayDelegatesToExecutionReplay() throws Exception {
        engine = buildEngine(false);
        ExecutionResult original = run();
        awaitCondition(() -> !store.findDeadLetters(null, 0, 10).isEmpty());
        DeadLetterRecord deadLetter = store.findDeadLetters(null, 0, 10).get(0);

        ExecutionHandle replay = engine.replayDeadLetter(deadLetter);
        replay.result().get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertThat(awaitStored(replay.executionId()).context())
                .containsEntry(NexoraEngine.REPLAY_OF_CONTEXT_KEY, original.executionId());
        assertThat(keysSeen.get(replay.executionId()).get("reserve"))
                .as("goes through replay(), so completed steps keep their keys")
                .isEqualTo(keysSeen.get(original.executionId()).get("reserve"));
    }

    @Test
    void deadLetterReplayFallsBackToDeadLetterWhenOriginalRecordIsGone() throws Exception {
        engine = buildEngine(false);
        failChargeTimes.set(0);
        DeadLetterRecord orphan = DeadLetterRecord.pending("dl-1", "gone-execution", GOAL,
                Map.of("orderId", "ORD-9"), "STEP_FAILED", "boom", Instant.now());

        ExecutionHandle replay = engine.replayDeadLetter(orphan);

        assertThat(replay.result().get(WAIT_SECONDS, TimeUnit.SECONDS).status()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(awaitStored(replay.executionId()).context())
                .containsEntry(NexoraEngine.REPLAY_OF_CONTEXT_KEY, "gone-execution")
                .containsEntry("orderId", "ORD-9");
    }

    @Test
    void replayOfCompletedExecutionIsRejected() throws Exception {
        failChargeTimes.set(0);
        engine = buildEngine(false);
        ExecutionResult original = run();
        assertThat(original.status()).isEqualTo(ExecutionStatus.COMPLETED);

        assertThatThrownBy(() -> engine.replay(original.executionId()))
                .isInstanceOf(ExecutionNotReplayableException.class)
                .satisfies(e -> assertThat(((ExecutionNotReplayableException) e).state()).isEqualTo(ExecutionState.COMPLETED));
    }

    @Test
    void replayOfCancelledExecutionIsAllowed() throws Exception {
        engine = buildEngine(false);
        String id = UUID.randomUUID().toString();
        store.createExecution(new ExecutionRecord(id, "trace", GOAL, Map.of("orderId", "ORD-1"),
                ExecutionState.CANCELLED, Instant.now(), Instant.now(), List.of()));
        failChargeTimes.set(0);

        assertThat(engine.replay(id).result().get(WAIT_SECONDS, TimeUnit.SECONDS).status())
                .isEqualTo(ExecutionStatus.COMPLETED);
    }

    @Test
    void replayOfExecutionStillRunningElsewhereIsRejected() {
        engine = buildEngine(false);
        String id = UUID.randomUUID().toString();
        store.createExecution(ExecutionRecord.started(id, "trace", GOAL, Map.of(), Instant.now()));

        assertThatThrownBy(() -> engine.replay(id)).isInstanceOf(ExecutionNotReplayableException.class);
    }

    @Test
    void replayOfUnknownExecutionFailsWithNotFound() {
        engine = buildEngine(false);

        assertThatThrownBy(() -> engine.replay("missing")).isInstanceOf(ExecutionNotFoundException.class);
    }

    @Test
    void replayRequiresPersistenceAndAnId() {
        try (NexoraEngine noStore = NexoraEngine.builder().build()) {
            assertThatThrownBy(() -> noStore.replay("any")).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> noStore.replay(" ")).isInstanceOf(IllegalArgumentException.class);
        }
    }

    private ExecutionResult run() throws Exception {
        return engine.execute(new Intent(GOAL, Map.of("orderId", "ORD-1"))).get(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    private ExecutionRecord awaitStored(String executionId) throws InterruptedException {
        awaitCondition(() -> store.findById(executionId).map(r -> r.state() != ExecutionState.RUNNING).orElse(false));
        return store.findById(executionId).orElseThrow();
    }

    private void awaitState(String executionId, ExecutionState state) throws InterruptedException {
        awaitCondition(() -> store.findById(executionId).map(r -> r.state() == state).orElse(false));
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
        return NexoraEngine.builder()
                .withExecutionStore(store)
                .withSagaEnabled(sagaEnabled)
                .withPlugin(plugin())
                .withStepDefinition(StepDefinition.builder("reserve", "reserve")
                        .withMatcher(goal -> goal.contains("order"))
                        .withCompensateCapabilityId("release")
                        .build())
                .withStepDefinition(StepDefinition.builder("charge", "charge")
                        .withMatcher(goal -> goal.contains("order"))
                        .dependsOn("reserve")
                        .build())
                .build();
    }

    private NexoraPlugin plugin() {
        return new NexoraPlugin() {
            @Override public PluginDescriptor descriptor() {
                return new PluginDescriptor("replay-test", "1.0", "replay test plugin", List.of(), null);
            }
            @Override public void initialize(PluginContext ctx) {}
            @Override public List<CapabilityProvider> capabilityProviders() {
                return List.of(
                        provider("reserve", req -> {
                            recordKey(req.executionId(), req.stepId(), req.idempotencyKey());
                            return CapabilityResult.success("reserved");
                        }),
                        provider("release", req -> CapabilityResult.success("released")),
                        provider("charge", req -> {
                            recordKey(req.executionId(), req.stepId(), req.idempotencyKey());
                            chargeCalls.incrementAndGet();
                            return failChargeTimes.getAndDecrement() > 0
                                    ? CapabilityResult.failure("DECLINED", "card declined")
                                    : CapabilityResult.success("charged");
                        }));
            }
            @Override public void shutdown() {}
        };
    }

    private void recordKey(String executionId, String stepId, String key) {
        keysSeen.computeIfAbsent(executionId, id -> new ConcurrentHashMap<>()).put(stepId, key);
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
