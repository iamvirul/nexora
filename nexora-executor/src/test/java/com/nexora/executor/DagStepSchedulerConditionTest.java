package com.nexora.executor;

import com.nexora.core.capability.CapabilityResult;
import com.nexora.core.context.ExecutionContext;
import com.nexora.core.context.TraceContext;
import com.nexora.core.execution.ExecutionResult;
import com.nexora.core.execution.StepResult;
import com.nexora.core.intent.Intent;
import com.nexora.core.plan.Step;
import com.nexora.core.plan.Plan;
import com.nexora.core.plan.condition.And;
import com.nexora.core.plan.condition.ContextValueEquals;
import com.nexora.core.plan.condition.Not;
import com.nexora.core.plan.condition.Or;
import com.nexora.core.capability.ResultStatus;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DagStepSchedulerConditionTest {

    @Test
    void stepIsSkippedWhenConditionIsFalse() {
        CapabilityRegistry registry = new DefaultCapabilityRegistry();
        AtomicBoolean executed = new AtomicBoolean(false);

        registry.register(descriptor("test_cap"), request -> {
            executed.set(true);
            return CapabilityResult.success(Map.of());
        });

        Step step = new Step(
                "s1", "test_cap", Map.of(), null, Set.of(), null, null, null,
                new ContextValueEquals("intent.context.skip", true) // this will be false
        );

        Plan plan = new Plan(List.of(step));
        ExecutionContext ctx = new ExecutionContext(
                new Intent("test", Map.of("skip", false)), 
                TraceContext.root()
        );

        DagStepScheduler scheduler = createScheduler(registry);
        ExecutionResult result = scheduler.schedule(plan, ctx).join();

        assertFalse(executed.get(), "Capability should not have executed");
        
        StepResult sr = result.stepResults().getFirst();
        assertEquals(ResultStatus.FAILURE, sr.capabilityResult().status());
        assertEquals("SKIPPED", sr.capabilityResult().failureCode());
    }

    @Test
    void stepExecutesWhenConditionIsTrue() {
        CapabilityRegistry registry = new DefaultCapabilityRegistry();
        AtomicBoolean executed = new AtomicBoolean(false);

        registry.register(descriptor("test_cap"), request -> {
            executed.set(true);
            return CapabilityResult.success(Map.of());
        });

        Step step = new Step(
                "s1", "test_cap", Map.of(), null, Set.of(), null, null, null,
                new ContextValueEquals("intent.context.execute", true) // this will be true
        );

        Plan plan = new Plan(List.of(step));
        ExecutionContext ctx = new ExecutionContext(
                new Intent("test", Map.of("execute", true)), 
                TraceContext.root()
        );

        DagStepScheduler scheduler = createScheduler(registry);
        ExecutionResult result = scheduler.schedule(plan, ctx).join();

        assertTrue(executed.get(), "Capability should have executed");
        assertEquals(ResultStatus.SUCCESS, result.stepResults().getFirst().capabilityResult().status());
    }

    @Test
    void composedConditionsEvaluateCorrectly() {
        CapabilityRegistry registry = new DefaultCapabilityRegistry();
        registry.register(descriptor("test_cap"), request -> CapabilityResult.success(Map.of()));

        Step stepAnd = new Step("and", "test_cap", Map.of(), null, Set.of(), null, null, null,
                new And(List.of(
                        new ContextValueEquals("intent.context.a", true),
                        new ContextValueEquals("intent.context.b", true)
                ))
        );
        Step stepOr = new Step("or", "test_cap", Map.of(), null, Set.of(), null, null, null,
                new Or(List.of(
                        new ContextValueEquals("intent.context.a", true),
                        new ContextValueEquals("intent.context.b", true)
                ))
        );
        Step stepNot = new Step("not", "test_cap", Map.of(), null, Set.of(), null, null, null,
                new Not(new ContextValueEquals("intent.context.a", true))
        );

        Plan plan = new Plan(List.of(stepAnd, stepOr, stepNot));
        // a = false, b = true
        // And -> false (skipped)
        // Or -> true (executed)
        // Not -> true (executed)
        ExecutionContext ctx = new ExecutionContext(
                new Intent("test", Map.of("a", false, "b", true)), 
                TraceContext.root()
        );

        DagStepScheduler scheduler = createScheduler(registry);
        ExecutionResult result = scheduler.schedule(plan, ctx).join();

        assertEquals(3, result.stepResults().size());
        
        StepResult srAnd = result.stepResults().stream().filter(s -> s.stepId().equals("and")).findFirst().get();
        assertEquals(ResultStatus.FAILURE, srAnd.capabilityResult().status());
        assertEquals("SKIPPED", srAnd.capabilityResult().failureCode());

        StepResult srOr = result.stepResults().stream().filter(s -> s.stepId().equals("or")).findFirst().get();
        assertEquals(ResultStatus.SUCCESS, srOr.capabilityResult().status());

        StepResult srNot = result.stepResults().stream().filter(s -> s.stepId().equals("not")).findFirst().get();
        assertEquals(ResultStatus.SUCCESS, srNot.capabilityResult().status());
    }

    private CapabilityDescriptor descriptor(String id) {
        return new CapabilityDescriptor(id, id, List.of(), List.of(), false, false, com.nexora.core.capability.CapabilityContract.none());
    }

    private DagStepScheduler createScheduler(CapabilityRegistry registry) {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        CapabilityInvoker invoker = new CapabilityInvoker(registry, new CapabilityContractMonitor());
        InterceptorPipeline pipeline = new InterceptorPipeline(List.of(), invoker);
        return new DagStepScheduler(pipeline, new DefaultRetryPolicyRegistry(), new InProcessEventBus(executor), executor, registry);
    }
}
