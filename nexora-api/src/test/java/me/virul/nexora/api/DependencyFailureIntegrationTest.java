package me.virul.nexora.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import me.virul.nexora.core.capability.CapabilityResult;
import me.virul.nexora.core.capability.ResultStatus;
import me.virul.nexora.core.execution.ExecutionResult;
import me.virul.nexora.core.execution.ExecutionStatus;
import me.virul.nexora.core.execution.StepResult;
import me.virul.nexora.core.intent.Intent;
import me.virul.nexora.event.CompensationStartedEvent;
import me.virul.nexora.event.PlanFailedEvent;
import me.virul.nexora.persistence.DeadLetterRecord;
import me.virul.nexora.persistence.DeadLetterReviewState;
import me.virul.nexora.persistence.jdbc.JdbcExecutionStore;
import me.virul.nexora.planner.model.StepDefinition;
import me.virul.nexora.spi.Capability;
import me.virul.nexora.spi.CapabilityDescriptor;
import me.virul.nexora.spi.CapabilityProvider;
import me.virul.nexora.spi.NexoraPlugin;
import me.virul.nexora.spi.PluginContext;
import me.virul.nexora.spi.PluginDescriptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The order workflow from #191, end to end: reserve, then validate, then charge. When validate
 * rejects the order, charge must never run, the failure must name validate, and saga compensation
 * must undo only the reservation.
 */
class DependencyFailureIntegrationTest {

    private static final String GOAL = "process order";

    private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
    private final CountDownLatch released = new CountDownLatch(1);
    private final JdbcExecutionStore store = JdbcExecutionStore.h2InMemory();
    private final NexoraEngine engine = NexoraEngine.builder()
            .withExecutionStore(store)
            .withSagaEnabled(true)
            .withPlugin(orderPlugin())
            .withStepDefinition(StepDefinition.builder("reserve", "reserve_stock")
                    .withMatcher(goal -> goal.contains("order"))
                    .withCompensateCapabilityId("release_stock")
                    .build())
            .withStepDefinition(StepDefinition.builder("validate", "validate_order")
                    .withMatcher(goal -> goal.contains("order"))
                    .dependsOn("reserve")
                    .build())
            .withStepDefinition(StepDefinition.builder("charge", "charge_payment")
                    .withMatcher(goal -> goal.contains("order"))
                    .withCompensateCapabilityId("refund_payment")
                    .dependsOn("validate")
                    .build())
            .build();

    @AfterEach
    void tearDown() {
        engine.close();
    }

    @Test
    void rejectedOrderIsNeverChargedAndOnlyTheReservationIsCompensated() throws Exception {
        List<String> compensated = new CopyOnWriteArrayList<>();
        engine.subscribeSync(CompensationStartedEvent.class, e -> compensated.add(e.stepId()));

        ExecutionResult result = engine.execute(new Intent(GOAL, Map.of())).get(10, TimeUnit.SECONDS);

        assertThat(result.status()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(callCount("charge_payment")).isZero();
        StepResult charge = stepResult(result, "charge");
        assertThat(charge.capabilityResult().status()).isEqualTo(ResultStatus.SKIPPED);
        assertThat(charge.capabilityResult().failureCode()).isEqualTo(CapabilityResult.DEPENDENCY_FAILED);

        assertThat(released.await(10, TimeUnit.SECONDS))
                .as("reservation released")
                .isTrue();
        assertThat(compensated).containsExactly("reserve");
        assertThat(callCount("refund_payment")).isZero();
    }

    @Test
    void failureIsReportedForTheFailedStepNotTheSkippedOne() throws Exception {
        List<PlanFailedEvent> failures = new CopyOnWriteArrayList<>();
        engine.subscribeSync(PlanFailedEvent.class, failures::add);

        ExecutionResult result = engine.execute(new Intent(GOAL, Map.of())).get(10, TimeUnit.SECONDS);

        assertThat(failures)
                .singleElement()
                .extracting(PlanFailedEvent::failedStepId)
                .isEqualTo("validate");
        List<DeadLetterRecord> deadLetters = store.findDeadLetters(DeadLetterReviewState.PENDING, 0, 10);
        assertThat(deadLetters).singleElement().satisfies(dl -> {
            assertThat(dl.executionId()).isEqualTo(result.executionId());
            assertThat(dl.failureMessage()).isEqualTo("over limit");
        });
    }

    private int callCount(String capabilityId) {
        AtomicInteger count = calls.get(capabilityId);
        return count == null ? 0 : count.get();
    }

    private static StepResult stepResult(ExecutionResult result, String stepId) {
        return result.stepResults().stream()
                .filter(r -> r.stepId().equals(stepId))
                .findFirst()
                .orElseThrow();
    }

    private NexoraPlugin orderPlugin() {
        Map<String, Capability> capabilities = Map.of(
                "reserve_stock", request -> CapabilityResult.success(Map.of("reserved", true)),
                "release_stock",
                        request -> {
                            released.countDown();
                            return CapabilityResult.success(Map.of());
                        },
                "validate_order", request -> CapabilityResult.failure("ORDER_REJECTED", "over limit"),
                "charge_payment", request -> CapabilityResult.success(Map.of("paymentId", "PAY-1")),
                "refund_payment", request -> CapabilityResult.success(Map.of()));
        return new NexoraPlugin() {
            @Override
            public PluginDescriptor descriptor() {
                return new PluginDescriptor("order-plugin", "1.0", "orders", List.of(), null);
            }

            @Override
            public void initialize(PluginContext context) {}

            @Override
            public List<CapabilityProvider> capabilityProviders() {
                return capabilities.entrySet().stream()
                        .map(entry -> provider(entry.getKey(), entry.getValue()))
                        .toList();
            }

            @Override
            public void shutdown() {}
        };
    }

    private CapabilityProvider provider(String id, Capability capability) {
        return new CapabilityProvider() {
            @Override
            public CapabilityDescriptor descriptor() {
                return new CapabilityDescriptor(id, id, List.of(), List.of(), true, false);
            }

            @Override
            public Capability create(PluginContext context) {
                return request -> {
                    calls.computeIfAbsent(id, key -> new AtomicInteger()).incrementAndGet();
                    return capability.execute(request);
                };
            }
        };
    }
}
