package com.nexora.executor;

import com.nexora.core.capability.CapabilityResult;
import com.nexora.core.context.ExecutionContext;
import com.nexora.core.context.TraceContext;
import com.nexora.core.execution.ExecutionResult;
import com.nexora.core.execution.ExecutionStatus;
import com.nexora.core.execution.StepResult;
import com.nexora.core.intent.Intent;
import com.nexora.core.plan.AddStepAmendment;
import com.nexora.core.plan.InputBinding;
import com.nexora.core.plan.ModifyInputAmendment;
import com.nexora.core.plan.Plan;
import com.nexora.core.plan.SkipStepAmendment;
import com.nexora.core.plan.Step;
import com.nexora.event.InProcessEventBus;
import com.nexora.registry.DefaultCapabilityRegistry;
import com.nexora.retry.DefaultRetryPolicyRegistry;
import com.nexora.spi.CapabilityDescriptor;
import com.nexora.spi.CapabilityRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DagStepSchedulerTest {

    @Test
    void appliesAddAndModifyAmendmentsBeforeDependentStep() {
        CapabilityRegistry registry = new DefaultCapabilityRegistry();
        AtomicBoolean auditExecuted = new AtomicBoolean(false);
        AtomicReference<String> finalMessage = new AtomicReference<>();

        Step auditStep = new Step("audit",
                "audit",
                Map.of("source", InputBinding.fromStep("first")),
                null,
                Set.of("first"),
                null,
                null,
                null
        );

        registry.register(descriptor("first"), request -> CapabilityResult.success(
                Map.of("validated", true),
                List.of(
                        new AddStepAmendment(auditStep),
                        new ModifyInputAmendment("final", "message", "patched")
                )));
        registry.register(descriptor("audit"), request -> {
            auditExecuted.set(true);
            return CapabilityResult.success(Map.of("audited", true));
        });
        registry.register(descriptor("final"), request -> {
            finalMessage.set((String) request.inputs().get("message"));
            return CapabilityResult.success("done");
        });

        try (TestHarness harness = new TestHarness(registry)) {
            Plan plan = new Plan(List.of(
                    Step.of("first", "first"),
                    new Step(
                            "final",
                            "final",
                            Map.of("message", InputBinding.literal("original")),
                            null,
                            Set.of("first"),
                            null,
                            null,
                            null
                    )
            ));

            ExecutionResult result = harness.scheduler.schedule(plan, context()).join();

            assertEquals(ExecutionStatus.COMPLETED, result.status());
            assertEquals(Set.of("first", "audit", "final"), stepIds(result));
            assertTrue(auditExecuted.get());
            assertEquals("patched", finalMessage.get());
        }
    }

    @Test
    void skipsPendingStepWhenSkipAmendmentIsReturned() {
        CapabilityRegistry registry = new DefaultCapabilityRegistry();
        AtomicInteger legacyCalls = new AtomicInteger();
        AtomicInteger finalCalls = new AtomicInteger();

        registry.register(descriptor("first"), request ->
                CapabilityResult.success(Map.of(), List.of(new SkipStepAmendment("legacy"))));
        registry.register(descriptor("legacy"), request -> {
            legacyCalls.incrementAndGet();
            return CapabilityResult.success("legacy");
        });
        registry.register(descriptor("final"), request -> {
            finalCalls.incrementAndGet();
            return CapabilityResult.success("final");
        });

        try (TestHarness harness = new TestHarness(registry)) {
            Plan plan = new Plan(List.of(
                    Step.of("first", "first"),
                    new Step("legacy", "legacy", Map.of(), null, Set.of("first"), null, null, null),
                    new Step("final", "final", Map.of(), null, Set.of("first"), null, null, null)
            ));

            ExecutionResult result = harness.scheduler.schedule(plan, context()).join();

            StepResult legacyResult = result.stepResults().stream()
                    .filter(r -> r.stepId().equals("legacy"))
                    .findFirst()
                    .orElseThrow();

            assertEquals(ExecutionStatus.COMPLETED, result.status());
            assertEquals(0, legacyCalls.get());
            assertEquals(1, finalCalls.get());
            assertFalse(legacyResult.succeeded());
            assertEquals("SKIPPED", legacyResult.capabilityResult().failureCode());
        }
    }

    @Test
    void rejectsCyclicPlansBeforeExecution() {
        CapabilityRegistry registry = new DefaultCapabilityRegistry();
        registry.register(descriptor("a"), request -> CapabilityResult.success("a"));
        registry.register(descriptor("b"), request -> CapabilityResult.success("b"));

        try (TestHarness harness = new TestHarness(registry)) {
            Plan cyclicPlan = new Plan(List.of(
                    new Step("a", "a", Map.of(), null, Set.of("b"), null, null, null),
                    new Step("b", "b", Map.of(), null, Set.of("a"), null, null, null)
            ));

            IllegalStateException ex = assertThrows(
                    IllegalStateException.class,
                    () -> harness.scheduler.schedule(cyclicPlan, context())
            );
            assertTrue(ex.getMessage().contains("Cycle detected"));
        }
    }

    @Test
    void completesImmediatelyWhenPlanHasNoSteps() {
        CapabilityRegistry registry = new DefaultCapabilityRegistry();

        try (TestHarness harness = new TestHarness(registry)) {
            ExecutionResult result = harness.scheduler
                    .schedule(new Plan(List.of()), context())
                    .join();

            assertEquals(ExecutionStatus.COMPLETED, result.status());
            assertTrue(result.stepResults().isEmpty());
        }
    }

    @Test
    void suppressesPendingStepsWhenHalted() {
        CapabilityRegistry registry = new DefaultCapabilityRegistry();
        AtomicInteger finalCalls = new AtomicInteger();

        registry.register(descriptor("first"), request -> CapabilityResult.success("first"));
        registry.register(descriptor("final"), request -> {
            finalCalls.incrementAndGet();
            return CapabilityResult.success("final");
        });

        try (TestHarness harness = new TestHarness(registry)) {
            Plan plan = new Plan(List.of(
                    Step.of("first", "first"),
                    new Step("final", "final", Map.of(), null, Set.of("first"), null, null, null)
            ));

            AtomicBoolean halted = new AtomicBoolean(true);
            DagStepScheduler.ScheduleSession session = harness.scheduler.schedule(plan, context(), halted);
            ExecutionResult result = session.future().join();

            // When halted is true from the start, steps return TIMED_OUT immediately
            assertEquals(ExecutionStatus.TIMED_OUT, result.status());
            assertEquals(0, finalCalls.get());
        }
    }

    private static ExecutionContext context() {
        return new ExecutionContext(new Intent("goal", Map.of()), TraceContext.root());
    }

    private static CapabilityDescriptor descriptor(String id) {
        return new CapabilityDescriptor(id, id, List.of(), List.of(), true, false);
    }

    private static Set<String> stepIds(ExecutionResult result) {
        return result.stepResults().stream().map(StepResult::stepId).collect(Collectors.toSet());
    }

    private static final class TestHarness implements AutoCloseable {
        private final ExecutorService executor;
        private final DagStepScheduler scheduler;

        private TestHarness(CapabilityRegistry registry) {
            this.executor = Executors.newFixedThreadPool(4);
            InterceptorPipeline pipeline = new InterceptorPipeline(
                    List.of(),
                    new CapabilityInvoker(registry, new CapabilityContractMonitor())
            );
            this.scheduler = new DagStepScheduler(
                    pipeline,
                    new DefaultRetryPolicyRegistry(),
                    new InProcessEventBus(executor),
                    executor,
                    registry
            );
        }

        @Override
        public void close() {
            executor.shutdownNow();
        }
    }
}
