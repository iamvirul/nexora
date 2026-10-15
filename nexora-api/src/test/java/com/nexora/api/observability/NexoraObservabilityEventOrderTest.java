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
import com.nexora.planner.model.StepDefinition;
import com.nexora.spi.Capability;
import com.nexora.spi.CapabilityDescriptor;
import com.nexora.spi.CapabilityProvider;
import com.nexora.spi.NexoraPlugin;
import com.nexora.spi.PluginContext;
import com.nexora.spi.PluginDescriptor;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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
        try {
            TimeUnit.MICROSECONDS.sleep(ThreadLocalRandom.current().nextInt(MAX_JITTER_MICROS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        task.run();
    });

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

                // No polling: the view is current by the time the result is handed back.
                ExecutionSnapshot execution = find(observability.processSnapshot(), executionId);
                assertThat(execution.status()).isEqualTo("COMPLETED");
                assertThat(execution.steps())
                        .extracting(StepSnapshot::stepId, StepSnapshot::status)
                        .containsExactlyInAnyOrder(tuple("charge", "COMPLETED"), tuple("receipt", "COMPLETED"));
                assertThat(stepOf(execution, "charge").output()).isEqualTo(Map.of("paymentId", "p-1"));
            }
            assertThat(observability.processSnapshot().activeExecutions()).isZero();
            assertThat(observability.scrapePrometheus())
                    .containsPattern("(?m)^nexora_step_completed_total\\{capability_id=\"charge\",} 25\\.0$");
        }
    }

    @Test
    void listenersReceiveTheFinalStateOfAnExecution() throws Exception {
        AtomicReference<ProcessSnapshot> latest = new AtomicReference<>();
        try (NexoraEngine engine = engine();
                NexoraObservability observability = NexoraObservability.attach(engine)) {
            observability.addSnapshotListener(latest::set);

            String executionId = engine.execute(new Intent("process order", Map.of()))
                    .get(WAIT_SECONDS, TimeUnit.SECONDS)
                    .executionId();

            long deadline = System.nanoTime() + Duration.ofSeconds(WAIT_SECONDS).toNanos();
            while (!isCompleted(latest.get(), executionId) && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertThat(isCompleted(latest.get(), executionId))
                    .as("a listener saw the execution complete")
                    .isTrue();
        }
    }

    private static boolean isCompleted(ProcessSnapshot snapshot, String executionId) {
        return snapshot != null
                && snapshot.executions().stream()
                        .anyMatch(e -> e.executionId().equals(executionId) && "COMPLETED".equals(e.status()));
    }

    private static ExecutionSnapshot find(ProcessSnapshot snapshot, String executionId) {
        return snapshot.executions().stream()
                .filter(e -> e.executionId().equals(executionId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("execution " + executionId + " missing from the snapshot"));
    }

    private static StepSnapshot stepOf(ExecutionSnapshot execution, String stepId) {
        return execution.steps().stream()
                .filter(s -> s.stepId().equals(stepId))
                .findFirst()
                .orElseThrow();
    }

    private static NexoraEngine engine() {
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

    private static NexoraPlugin plugin() {
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
                        provider("charge", req -> CapabilityResult.success(Map.of("paymentId", "p-1"))),
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
