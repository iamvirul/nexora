package me.virul.nexora.executor;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import me.virul.nexora.core.capability.CapabilityContract;
import me.virul.nexora.core.capability.CapabilityResult;
import me.virul.nexora.core.capability.ResultStatus;
import me.virul.nexora.core.context.ExecutionContext;
import me.virul.nexora.core.context.TraceContext;
import me.virul.nexora.core.execution.ExecutionResult;
import me.virul.nexora.core.execution.ExecutionStatus;
import me.virul.nexora.core.execution.StepResult;
import me.virul.nexora.core.intent.Intent;
import me.virul.nexora.core.plan.Plan;
import me.virul.nexora.core.plan.Step;
import me.virul.nexora.core.plan.StepCondition;
import me.virul.nexora.core.plan.condition.ContextValueEquals;
import me.virul.nexora.event.InProcessEventBus;
import me.virul.nexora.registry.DefaultCapabilityRegistry;
import me.virul.nexora.retry.DefaultRetryPolicyRegistry;
import me.virul.nexora.spi.CapabilityDescriptor;
import me.virul.nexora.spi.CapabilityRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** A step whose dependency failed is skipped, not run (#191). */
class DagStepSchedulerDependencyFailureTest {

    private static final String OK = "ok_cap";
    private static final String FAIL = "fail_cap";
    private static final String THROW = "throw_cap";

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final CapabilityRegistry registry = new DefaultCapabilityRegistry();
    private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();

    DagStepSchedulerDependencyFailureTest() {
        registry.register(descriptor(OK), request -> {
            count(request.stepId());
            return CapabilityResult.success(Map.of("step", request.stepId()));
        });
        registry.register(descriptor(FAIL), request -> {
            count(request.stepId());
            return CapabilityResult.failure("ORDER_REJECTED", "over limit");
        });
        registry.register(descriptor(THROW), request -> {
            count(request.stepId());
            throw new IllegalStateException("boom");
        });
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void skipsDependentWhenDependencyFails() {
        ExecutionResult result = run(step("validate", FAIL), step("charge", OK, "validate"));

        assertThat(result.status()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(callCount("charge")).isZero();
        assertSkippedForFailedDependency(result, "charge", "validate");
        assertThat(resultOf(result, "validate").capabilityResult().failureCode())
                .isEqualTo("ORDER_REJECTED");
    }

    @Test
    void skipsDependentWhenDependencyThrows() {
        ExecutionResult result = run(step("validate", THROW), step("charge", OK, "validate"));

        assertThat(callCount("charge")).isZero();
        assertSkippedForFailedDependency(result, "charge", "validate");
    }

    @Test
    void skipPropagatesDownAChain() {
        ExecutionResult result = run(step("a", FAIL), step("b", OK, "a"), step("c", OK, "b"), step("d", OK, "c"));

        assertThat(callCount("b") + callCount("c") + callCount("d")).isZero();
        assertSkippedForFailedDependency(result, "b", "a");
        assertSkippedForFailedDependency(result, "c", "b");
        assertSkippedForFailedDependency(result, "d", "c");
    }

    @Test
    void skipsJoinWhenAnyBranchFails() {
        ExecutionResult result = run(step("left", OK), step("right", FAIL), step("join", OK, "left", "right"));

        assertThat(callCount("left")).isOne();
        assertThat(callCount("join")).isZero();
        assertSkippedForFailedDependency(result, "join", "right");
    }

    @Test
    void independentBranchStillRunsAfterAFailure() {
        ExecutionResult result =
                run(step("a", FAIL), step("b", OK, "a"), step("other", OK), step("after_other", OK, "other"));

        assertThat(callCount("other")).isOne();
        assertThat(callCount("after_other")).isOne();
        assertThat(resultOf(result, "after_other").succeeded()).isTrue();
        assertThat(result.status()).isEqualTo(ExecutionStatus.FAILED);
    }

    @Test
    void dependentStillRunsWhenDependencyIsSkippedByCondition() {
        StepCondition never = new ContextValueEquals("intent.context.run", true);
        ExecutionResult result = run(
                new Step("optional", OK, Map.of(), null, Set.of(), null, null, null, never),
                step("next", OK, "optional"));

        assertThat(resultOf(result, "optional").capabilityResult().status()).isEqualTo(ResultStatus.SKIPPED);
        assertThat(callCount("next")).isOne();
        assertThat(result.status()).isEqualTo(ExecutionStatus.COMPLETED);
    }

    private ExecutionResult run(Step... steps) {
        ExecutionContext ctx = new ExecutionContext(new Intent("test", Map.of("run", false)), TraceContext.root());
        CapabilityInvoker invoker = new CapabilityInvoker(registry, new CapabilityContractMonitor());
        DagStepScheduler scheduler = new DagStepScheduler(
                new InterceptorPipeline(List.of(), invoker),
                new DefaultRetryPolicyRegistry(),
                new InProcessEventBus(executor),
                executor,
                registry);
        return scheduler.schedule(new Plan(List.of(steps)), ctx).join();
    }

    private static Step step(String id, String capabilityId, String... dependsOn) {
        return new Step(id, capabilityId, Map.of(), null, Set.of(dependsOn), null, null, null, null);
    }

    private static void assertSkippedForFailedDependency(ExecutionResult result, String stepId, String dependency) {
        CapabilityResult outcome = resultOf(result, stepId).capabilityResult();
        assertThat(outcome.status()).isEqualTo(ResultStatus.SKIPPED);
        assertThat(outcome.failureCode()).isEqualTo(CapabilityResult.DEPENDENCY_FAILED);
        assertThat(outcome.failureMessage()).contains("'" + dependency + "'");
    }

    private static StepResult resultOf(ExecutionResult result, String stepId) {
        return result.stepResults().stream()
                .filter(r -> r.stepId().equals(stepId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no result for step " + stepId));
    }

    private void count(String stepId) {
        calls.computeIfAbsent(stepId, id -> new AtomicInteger()).incrementAndGet();
    }

    private int callCount(String stepId) {
        AtomicInteger count = calls.get(stepId);
        return count == null ? 0 : count.get();
    }

    private static CapabilityDescriptor descriptor(String id) {
        return new CapabilityDescriptor(id, id, List.of(), List.of(), false, false, CapabilityContract.none());
    }
}
