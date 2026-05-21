package com.nexora.executor;

import com.nexora.core.capability.CapabilityRequest;
import com.nexora.core.capability.CapabilityResult;
import com.nexora.core.context.ExecutionContext;
import com.nexora.core.execution.ExecutionResult;
import com.nexora.core.execution.ExecutionStatus;
import com.nexora.core.execution.StepResult;
import com.nexora.core.plan.AddStepAmendment;
import com.nexora.core.plan.InputBinding;
import com.nexora.core.plan.ModifyInputAmendment;
import com.nexora.core.plan.Plan;
import com.nexora.core.plan.PlanAmendment;
import com.nexora.core.plan.SkipStepAmendment;
import com.nexora.core.plan.Step;
import com.nexora.event.ExecutionEventBus;
import com.nexora.event.PlanAmendedEvent;
import com.nexora.event.StepCompletedEvent;
import com.nexora.event.StepFailedEvent;
import com.nexora.event.StepStartedEvent;
import com.nexora.retry.RetryPolicyRegistry;
import com.nexora.spi.CapabilityRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Schedules plan steps as a DAG, executing independent steps in parallel.
 *
 * Supports reactive plan amendment: when a step's CapabilityResult carries
 * PlanAmendment entries, the scheduler applies them before dependent steps start:
 *   - AddStepAmendment  — injects a new step into the live DAG
 *   - SkipStepAmendment — marks a pending step as skipped
 *   - ModifyInputAmendment — overrides an input for a pending step
 *
 * Amendment processing is atomic with respect to the parent step's completion
 * counter, so there is no window in which a newly added step can be missed by
 * the final result collector.
 */
public final class DagStepScheduler {

    private static final Logger log = LoggerFactory.getLogger(DagStepScheduler.class);

    private final InterceptorPipeline pipeline;
    private final RetryPolicyRegistry retryPolicyRegistry;
    private final ExecutionEventBus eventBus;
    private final Executor executor;
    private final CapabilityRegistry capabilityRegistry;

    public DagStepScheduler(
            InterceptorPipeline pipeline,
            RetryPolicyRegistry retryPolicyRegistry,
            ExecutionEventBus eventBus,
            Executor executor,
            CapabilityRegistry capabilityRegistry) {
        this.pipeline = Objects.requireNonNull(pipeline);
        this.retryPolicyRegistry = Objects.requireNonNull(retryPolicyRegistry);
        this.eventBus = Objects.requireNonNull(eventBus);
        this.executor = Objects.requireNonNull(executor);
        this.capabilityRegistry = Objects.requireNonNull(capabilityRegistry);
    }

    public CompletableFuture<ExecutionResult> schedule(Plan plan, ExecutionContext ctx) {
        validateNoCycles(plan);

        if (plan.getSteps().isEmpty()) {
            return CompletableFuture.completedFuture(
                    new ExecutionResult(ctx.getExecutionId(), ExecutionStatus.COMPLETED, List.of())
            );
        }

        // Mutable futures map — grows as AddStepAmendments inject new steps
        ConcurrentHashMap<String, CompletableFuture<StepResult>> futures = new ConcurrentHashMap<>();
        ConcurrentHashMap<String, StepResult> completedResults = new ConcurrentHashMap<>();
        Set<String> skippedSteps = ConcurrentHashMap.newKeySet();

        CompletableFuture<ExecutionResult> done = new CompletableFuture<>();
        AtomicInteger pending = new AtomicInteger(plan.getSteps().size());

        Runnable onStepDone = () -> {
            if (pending.decrementAndGet() == 0) {
                done.complete(collectResults(ctx.getExecutionId(), completedResults));
            }
        };

        for (Step step : plan.getSteps()) {
            submitStep(step, futures, completedResults, skippedSteps, pending, onStepDone, ctx);
        }

        return done;
    }

    private void submitStep(
            Step step,
            ConcurrentHashMap<String, CompletableFuture<StepResult>> futures,
            ConcurrentHashMap<String, StepResult> completedResults,
            Set<String> skippedSteps,
            AtomicInteger pending,
            Runnable onStepDone,
            ExecutionContext ctx) {

        CompletableFuture<Void> prerequisite = buildPrerequisite(step, futures);

        CompletableFuture<StepResult> stepFuture = prerequisite
                .thenApplyAsync(ignored -> {
                    if (skippedSteps.contains(step.id())) {
                        log.info("Step skipped by amendment id={}", step.id());
                        return new StepResult(step.id(),
                                CapabilityResult.failure("SKIPPED", "Step skipped by plan amendment"));
                    }
                    return executeStep(step, ctx);
                }, executor)
                .handle((result, ex) -> {
                    if (ex != null) {
                        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                        log.error("Step threw unexpectedly id={} capability={}",
                                step.id(), step.capabilityId(), cause);
                        eventBus.publish(new StepFailedEvent(
                                ctx.getExecutionId(), step.id(), step.capabilityId(),
                                ctx.getTraceContext().traceId(),
                                "INTERNAL_ERROR", cause.getMessage(),
                                java.time.Duration.ZERO, java.time.Instant.now()
                        ));
                        return new StepResult(step.id(),
                                CapabilityResult.failure("INTERNAL_ERROR", cause.getMessage()));
                    }
                    return result;
                })
                .whenComplete((result, ex) -> {
                    if (result != null) {
                        completedResults.put(step.id(), result);
                        // Process amendments before decrementing — keeps pending count consistent
                        applyAmendments(result.capabilityResult().amendments(),
                                futures, completedResults, skippedSteps, pending, onStepDone, ctx);
                    }
                    onStepDone.run();
                });

        futures.put(step.id(), stepFuture);
    }

    private void applyAmendments(
            List<PlanAmendment> amendments,
            ConcurrentHashMap<String, CompletableFuture<StepResult>> futures,
            ConcurrentHashMap<String, StepResult> completedResults,
            Set<String> skippedSteps,
            AtomicInteger pending,
            Runnable onStepDone,
            ExecutionContext ctx) {

        for (PlanAmendment amendment : amendments) {
            switch (amendment) {
                case AddStepAmendment add -> {
                    if (futures.containsKey(add.step().id())) {
                        log.warn("AddStepAmendment ignored — step id already exists: {}", add.step().id());
                        continue;
                    }
                    log.info("Plan amendment: adding step id={}", add.step().id());
                    pending.incrementAndGet();
                    eventBus.publish(new PlanAmendedEvent(
                            ctx.getExecutionId(), ctx.getTraceContext().traceId(),
                            "ADD_STEP", add.step().id(), Instant.now()));
                    submitStep(add.step(), futures, completedResults, skippedSteps, pending, onStepDone, ctx);
                }
                case SkipStepAmendment skip -> {
                    log.info("Plan amendment: skipping step id={}", skip.stepId());
                    skippedSteps.add(skip.stepId());
                    eventBus.publish(new PlanAmendedEvent(
                            ctx.getExecutionId(), ctx.getTraceContext().traceId(),
                            "SKIP_STEP", skip.stepId(), Instant.now()));
                }
                case ModifyInputAmendment modify -> {
                    log.info("Plan amendment: modifying input stepId={} key={}", modify.stepId(), modify.inputKey());
                    ctx.putInputOverride(modify.stepId(), modify.inputKey(), modify.value());
                    eventBus.publish(new PlanAmendedEvent(
                            ctx.getExecutionId(), ctx.getTraceContext().traceId(),
                            "MODIFY_INPUT", modify.stepId(), Instant.now()));
                }
            }
        }
    }

    private StepResult executeStep(Step step, ExecutionContext ctx) {
        log.debug("Starting step id={} capability={} executionId={}",
                step.id(), step.capabilityId(), ctx.getExecutionId());

        Map<String, Object> resolvedInputs = resolveInputs(step, ctx);

        Duration effectiveTimeout = step.timeout() != null ? step.timeout()
                : capabilityRegistry.findDescriptor(step.capabilityId())
                        .filter(d -> d.contract().hasLatencySla())
                        .map(d -> d.contract().expectedP99Latency())
                        .orElse(null);

        CapabilityRequest request = new CapabilityRequest(
                step.capabilityId(),
                step.id(),
                UUID.randomUUID().toString(),
                resolvedInputs,
                ctx.getTraceContext().childSpan(),
                effectiveTimeout
        );

        eventBus.publish(new StepStartedEvent(
                ctx.getExecutionId(), step.id(), step.capabilityId(),
                request.idempotencyKey(),
                request.traceContext().traceId(), request.traceContext().spanId(),
                Instant.now()
        ));

        Instant start = Instant.now();
        CapabilityResult result = pipeline.execute(request);
        Duration elapsed = Duration.between(start, Instant.now());

        if (result.succeeded()) {
            if (step.outputKey() != null) {
                ctx.put(step.outputKey(), result.output());
            }
            ctx.recordStepOutput(step.id(), result.output());

            eventBus.publish(new StepCompletedEvent(
                    ctx.getExecutionId(), step.id(), step.capabilityId(),
                    request.traceContext().traceId(), elapsed, Instant.now()
            ));
            log.debug("Step completed id={} elapsed={}ms", step.id(), elapsed.toMillis());
        } else {
            eventBus.publish(new StepFailedEvent(
                    ctx.getExecutionId(), step.id(), step.capabilityId(),
                    request.traceContext().traceId(),
                    result.failureCode(), result.failureMessage(),
                    elapsed, Instant.now()
            ));
            log.warn("Step failed id={} code={} message={}",
                    step.id(), result.failureCode(), result.failureMessage());
        }

        return new StepResult(step.id(), result);
    }

    private Map<String, Object> resolveInputs(Step step, ExecutionContext ctx) {
        Map<String, Object> resolved = new HashMap<>();
        // ModifyInputAmendment overrides take priority over declared bindings
        Map<String, Object> overrides = ctx.getInputOverrides(step.id());
        for (Map.Entry<String, InputBinding> entry : step.inputs().entrySet()) {
            String key = entry.getKey();
            resolved.put(key, overrides.containsKey(key)
                    ? overrides.get(key)
                    : entry.getValue().resolve(ctx));
        }
        overrides.forEach(resolved::putIfAbsent);
        return resolved;
    }

    private CompletableFuture<Void> buildPrerequisite(
            Step step,
            ConcurrentHashMap<String, CompletableFuture<StepResult>> futures) {

        if (step.dependsOn().isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        List<CompletableFuture<StepResult>> deps = new ArrayList<>();
        for (String depId : step.dependsOn()) {
            CompletableFuture<StepResult> dep = futures.get(depId);
            if (dep == null) {
                throw new IllegalStateException(
                        "Step '" + step.id() + "' declares dependency on '" + depId +
                        "' but that step is not in the plan. " +
                        "AddStepAmendment dependencies must reference existing steps.");
            }
            deps.add(dep);
        }

        return CompletableFuture.allOf(deps.toArray(new CompletableFuture[0]));
    }

    private ExecutionResult collectResults(
            String executionId,
            ConcurrentHashMap<String, StepResult> completedResults) {

        List<StepResult> results = new ArrayList<>(completedResults.values());
        ExecutionStatus overallStatus = ExecutionStatus.COMPLETED;

        for (StepResult result : results) {
            if (!result.succeeded() && !isSkipped(result)) {
                overallStatus = ExecutionStatus.FAILED;
                break;
            }
        }

        return new ExecutionResult(executionId, overallStatus, results);
    }

    private boolean isSkipped(StepResult result) {
        return result.capabilityResult().failureCode() != null &&
                result.capabilityResult().failureCode().equals("SKIPPED");
    }

    private void validateNoCycles(Plan plan) {
        Map<String, Step> stepById = new HashMap<>();
        for (Step step : plan.getSteps()) {
            stepById.put(step.id(), step);
        }
        Map<String, Integer> color = new HashMap<>();
        for (Step step : plan.getSteps()) {
            if (!color.containsKey(step.id())) {
                dfsCheckCycle(step.id(), stepById, color);
            }
        }
    }

    private void dfsCheckCycle(String stepId, Map<String, Step> stepById, Map<String, Integer> color) {
        color.put(stepId, 1);
        Step step = stepById.get(stepId);
        if (step != null) {
            for (String dep : step.dependsOn()) {
                int depColor = color.getOrDefault(dep, 0);
                if (depColor == 1) {
                    throw new IllegalStateException(
                            "Cycle detected in plan dependency graph involving step: " + dep);
                }
                if (depColor == 0) {
                    dfsCheckCycle(dep, stepById, color);
                }
            }
        }
        color.put(stepId, 2);
    }
}
