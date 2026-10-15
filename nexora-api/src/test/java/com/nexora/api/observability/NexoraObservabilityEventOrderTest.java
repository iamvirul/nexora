package com.nexora.api.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.nexora.api.NexoraEngine;
import com.nexora.api.observability.NexoraObservability.ExecutionSnapshot;
import com.nexora.api.observability.NexoraObservability.ProcessSnapshot;
import com.nexora.api.observability.NexoraObservability.StepSnapshot;
import com.nexora.core.capability.CapabilityResult;
import com.nexora.core.execution.ExecutionResult;
import com.nexora.core.intent.Intent;
import com.nexora.persistence.StepOutput;
import com.nexora.planner.model.StepDefinition;
import com.nexora.spi.Capability;
import com.nexora.spi.CapabilityDescriptor;
import com.nexora.spi.CapabilityProvider;
import com.nexora.spi.NexoraPlugin;
import com.nexora.spi.PluginContext;
import com.nexora.spi.PluginDescriptor;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

/**
 * The event bus dispatches each event on its own task, so a fast step's events can be handled in
 * any order. The process view must still match what happened.
 */
class NexoraObservabilityEventOrderTest {

    private static final int EXECUTIONS = 25;
    private static final long WAIT_SECONDS = 10;
    private static final int MAX_JITTER_MICROS = 3_000;

    /** Runs every task on a virtual thread after a random delay, so tasks finish out of submission order. */
    private static final Executor JITTERED = task -> Thread.ofVirtual().start(() -> {
        jitter();
        task.run();
    });

    private final AtomicReference<Object> chargeOutput = new AtomicReference<>(Map.of("paymentId", "p-1"));

    @Test
    void snapshotReflectsEveryFinishedExecutionWhenEventsAreDispatchedOutOfOrder() throws Exception {
        try (NexoraEngine engine = engine();
                NexoraObservability observability = NexoraObservability.attach(engine, EXECUTIONS, 50)) {
            List<CompletableFuture<ExecutionResult>> runs = new ArrayList<>();
            for (int i = 0; i < EXECUTIONS; i++) {
                runs.add(engine.execute(new Intent("process order", Map.of("n", i))));
            }

            for (CompletableFuture<ExecutionResult> run : runs) {
                String executionId = run.get(WAIT_SECONDS, TimeUnit.SECONDS).executionId();

                // No polling: statuses are current by the time the result is handed back.
                ExecutionSnapshot execution = find(observability.processSnapshot(), executionId);
                assertThat(execution.status()).isEqualTo("COMPLETED");
                assertThat(execution.steps())
                        .extracting(StepSnapshot::stepId, StepSnapshot::status)
                        .containsExactlyInAnyOrder(tuple("charge", "COMPLETED"), tuple("receipt", "COMPLETED"));
            }
            for (CompletableFuture<ExecutionResult> run : runs) {
                String executionId = run.join().executionId();
                // Outputs are converted off the engine's threads, so they may land just after.
                await(() -> hasChargeOutput(observability.processSnapshot(), executionId));
            }
            assertThat(observability.processSnapshot().activeExecutions()).isZero();
            assertThat(observability.scrapePrometheus())
                    .containsPattern("(?m)^nexora_step_completed_total\\{capability_id=\"charge\",} 25\\.0$");
        }
    }

    @Test
    void stepOutputIsNotSerialisedOnTheEngineThread() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        chargeOutput.set(new BlockingOutput(release));
        try (NexoraEngine engine = engine();
                NexoraObservability observability = NexoraObservability.attach(engine)) {
            String executionId;
            try {
                // Writing the output as JSON blocks until released; the execution must not wait for it.
                executionId = engine.execute(new Intent("process order", Map.of()))
                        .get(WAIT_SECONDS, TimeUnit.SECONDS)
                        .executionId();
                assertThat(find(observability.processSnapshot(), executionId).status())
                        .isEqualTo("COMPLETED");
            } finally {
                release.countDown();
            }

            // Not a map, so it is recorded under "value".
            Map<String, ?> expected = Map.of(StepOutput.VALUE_KEY, Map.of("paymentId", "p-1"));
            await(() -> hasChargeOutput(observability.processSnapshot(), executionId, expected));
        }
    }

    @Test
    void listenersNeverReceiveAnOlderStateAfterANewerOne() throws Exception {
        List<ProcessSnapshot> delivered = Collections.synchronizedList(new ArrayList<>());
        try (NexoraEngine engine = engine();
                NexoraObservability observability = NexoraObservability.attach(engine, EXECUTIONS, 50)) {
            // A slow listener widens the window in which two broadcasts overlap.
            observability.addSnapshotListener(snapshot -> {
                delivered.add(snapshot);
                jitter();
            });
            List<CompletableFuture<ExecutionResult>> runs = new ArrayList<>();
            for (int i = 0; i < EXECUTIONS; i++) {
                runs.add(engine.execute(new Intent("process order", Map.of("n", i))));
            }
            List<String> executionIds = new ArrayList<>();
            for (CompletableFuture<ExecutionResult> run : runs) {
                executionIds.add(run.get(WAIT_SECONDS, TimeUnit.SECONDS).executionId());
            }

            await(() -> {
                ProcessSnapshot last = lastOf(delivered);
                return last != null && executionIds.stream().allMatch(id -> hasChargeOutput(last, id));
            });

            List<ProcessSnapshot> sequence;
            synchronized (delivered) {
                sequence = List.copyOf(delivered);
            }
            for (String executionId : executionIds) {
                int previousProgress = -1;
                for (ProcessSnapshot snapshot : sequence) {
                    int progress = progressOf(snapshot, executionId);
                    assertThat(progress)
                            .as("execution %s went backwards between delivered snapshots", executionId)
                            .isGreaterThanOrEqualTo(previousProgress);
                    previousProgress = progress;
                }
            }
        }
    }

    /**
     * How far an execution has got in one snapshot: finished steps, a step's output, and the
     * execution's own completion each add one. -1 when the snapshot does not include it yet.
     */
    private static int progressOf(ProcessSnapshot snapshot, String executionId) {
        return snapshot.executions().stream()
                .filter(e -> e.executionId().equals(executionId))
                .findFirst()
                .map(e -> e.completedSteps()
                        + (int) e.steps().stream()
                                .filter(s -> !s.output().isEmpty())
                                .count()
                        + ("COMPLETED".equals(e.status()) ? 1 : 0))
                .orElse(-1);
    }

    private static ProcessSnapshot lastOf(List<ProcessSnapshot> delivered) {
        synchronized (delivered) {
            return delivered.isEmpty() ? null : delivered.get(delivered.size() - 1);
        }
    }

    private static boolean hasChargeOutput(ProcessSnapshot snapshot, String executionId) {
        return hasChargeOutput(snapshot, executionId, Map.of("paymentId", "p-1"));
    }

    private static boolean hasChargeOutput(ProcessSnapshot snapshot, String executionId, Map<String, ?> expected) {
        return snapshot.executions().stream()
                .filter(e -> e.executionId().equals(executionId) && "COMPLETED".equals(e.status()))
                .flatMap(e -> e.steps().stream())
                .anyMatch(s -> s.stepId().equals("charge") && s.output().equals(expected));
    }

    private static ExecutionSnapshot find(ProcessSnapshot snapshot, String executionId) {
        return snapshot.executions().stream()
                .filter(e -> e.executionId().equals(executionId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("execution " + executionId + " missing from the snapshot"));
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(WAIT_SECONDS).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Condition not met within " + WAIT_SECONDS + "s");
            }
            Thread.sleep(10);
        }
    }

    private static void jitter() {
        try {
            TimeUnit.MICROSECONDS.sleep(ThreadLocalRandom.current().nextInt(MAX_JITTER_MICROS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Serialises to {@code {"paymentId":"p-1"}}, but only once released. */
    public static final class BlockingOutput {
        private final CountDownLatch release;

        BlockingOutput(CountDownLatch release) {
            this.release = release;
        }

        public String getPaymentId() throws InterruptedException {
            if (!release.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("never released");
            }
            return "p-1";
        }
    }

    private NexoraEngine engine() {
        return NexoraEngine.builder()
                .withExecutor(JITTERED)
                .withPlugin(plugin())
                .withStepDefinition(StepDefinition.builder("charge", "charge")
                        .withMatcher(goal -> goal.contains("order"))
                        .build())
                .withStepDefinition(StepDefinition.builder("receipt", "receipt")
                        .withMatcher(goal -> goal.contains("order"))
                        .dependsOn("charge")
                        .build())
                .build();
    }

    private NexoraPlugin plugin() {
        return new NexoraPlugin() {
            @Override
            public PluginDescriptor descriptor() {
                return new PluginDescriptor("event-order-test", "1.0", "event order test plugin", List.of(), null);
            }

            @Override
            public void initialize(PluginContext ctx) {}

            @Override
            public List<CapabilityProvider> capabilityProviders() {
                return List.of(
                        provider("charge", req -> CapabilityResult.success(chargeOutput.get())),
                        provider("receipt", req -> CapabilityResult.success("sent")));
            }

            @Override
            public void shutdown() {}
        };
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
