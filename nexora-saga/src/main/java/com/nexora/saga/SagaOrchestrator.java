package com.nexora.saga;

import com.nexora.core.capability.CapabilityRequest;
import com.nexora.core.capability.CapabilityResult;
import com.nexora.core.context.ExecutionContext;
import com.nexora.core.execution.ExecutionResult;
import com.nexora.core.execution.StepResult;
import com.nexora.core.plan.Plan;
import com.nexora.core.plan.Step;
import com.nexora.event.CompensationCompletedEvent;
import com.nexora.event.CompensationFailedEvent;
import com.nexora.event.CompensationStartedEvent;
import com.nexora.event.ExecutionEventBus;
import com.nexora.executor.InterceptorPipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Orchestration-based saga coordinator.
 *
 * On partial execution failure, collects all successfully completed steps that declared a
 * compensateCapabilityId and runs their compensations in reverse topological order
 * (i.e. the step closest to the failure compensates first, back toward the root).
 *
 * Compensation is sequential and best-effort: a failed compensation is logged and skipped,
 * but the remaining compensations still run. The caller receives a CompletableFuture that
 * resolves once all compensations have been attempted.
 *
 * Each compensation receives the original step's resolved inputs plus the execution context
 * so the compensating capability has all the data it needs to undo the work.
 */
public final class SagaOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(SagaOrchestrator.class);

    private final InterceptorPipeline pipeline;
    private final ExecutionEventBus eventBus;
    private final Executor executor;

    public SagaOrchestrator(InterceptorPipeline pipeline, ExecutionEventBus eventBus, Executor executor) {
        this.pipeline = Objects.requireNonNull(pipeline);
        this.eventBus = Objects.requireNonNull(eventBus);
        this.executor = Objects.requireNonNull(executor);
    }

    /**
     * Triggers compensation for all completed, compensable steps.
     * Returns a future that resolves once all compensations have been attempted.
     */
    public CompletableFuture<Void> compensate(Plan plan, ExecutionResult result, ExecutionContext ctx) {
        List<Step> toCompensate = resolveCompensationOrder(plan, result);

        if (toCompensate.isEmpty()) {
            log.debug("Saga: no compensable steps for executionId={}", ctx.getExecutionId());
            return CompletableFuture.completedFuture(null);
        }

        log.info("Saga: starting compensation for {} steps, executionId={}",
                toCompensate.size(), ctx.getExecutionId());

        return CompletableFuture.runAsync(() -> {
            for (Step step : toCompensate) {
                runCompensation(step, ctx);
            }
        }, executor);
    }

    private void runCompensation(Step step, ExecutionContext ctx) {
        String compensateCapabilityId = step.compensateCapabilityId();
        log.info("Saga: compensating step={} via capability={}", step.id(), compensateCapabilityId);

        Instant start = Instant.now();
        eventBus.publish(new CompensationStartedEvent(
                ctx.getExecutionId(), ctx.getTraceContext().traceId(),
                step.id(), compensateCapabilityId, start));

        CapabilityRequest request = new CapabilityRequest(
                compensateCapabilityId,
                step.id() + "_compensate",
                UUID.randomUUID().toString(),
                buildCompensationInputs(step, ctx),
                ctx.getTraceContext().childSpan(),
                null
        );

        try {
            CapabilityResult result = pipeline.execute(request);
            Duration elapsed = Duration.between(start, Instant.now());

            if (result.succeeded()) {
                log.info("Saga: compensation succeeded step={} elapsed={}ms", step.id(), elapsed.toMillis());
                eventBus.publish(new CompensationCompletedEvent(
                        ctx.getExecutionId(), ctx.getTraceContext().traceId(),
                        step.id(), compensateCapabilityId, elapsed, Instant.now()));
            } else {
                log.error("Saga: compensation failed step={} code={} message={}",
                        step.id(), result.failureCode(), result.failureMessage());
                eventBus.publish(new CompensationFailedEvent(
                        ctx.getExecutionId(), ctx.getTraceContext().traceId(),
                        step.id(), compensateCapabilityId,
                        result.failureCode(), result.failureMessage(), elapsed, Instant.now()));
            }
        } catch (Exception e) {
            Duration elapsed = Duration.between(start, Instant.now());
            log.error("Saga: compensation threw step={}", step.id(), e);
            eventBus.publish(new CompensationFailedEvent(
                    ctx.getExecutionId(), ctx.getTraceContext().traceId(),
                    step.id(), compensateCapabilityId,
                    "COMPENSATION_EXCEPTION", e.getMessage(), elapsed, Instant.now()));
        }
    }

    private Map<String, Object> buildCompensationInputs(Step step, ExecutionContext ctx) {
        Map<String, Object> inputs = new HashMap<>();
        // Pass all resolved step outputs back so the compensator has full context
        for (Map.Entry<String, com.nexora.core.plan.InputBinding> entry : step.inputs().entrySet()) {
            Object resolved = entry.getValue().resolve(ctx);
            if (resolved != null) inputs.put(entry.getKey(), resolved);
        }
        inputs.put("_executionId", ctx.getExecutionId());
        inputs.put("_stepId", step.id());
        return Map.copyOf(inputs);
    }

    /**
     * Returns compensable steps in reverse topological order.
     * Only steps that (a) completed successfully and (b) declared a compensateCapabilityId are included.
     */
    private List<Step> resolveCompensationOrder(Plan plan, ExecutionResult result) {
        // Build set of successfully completed step IDs
        java.util.Set<String> succeeded = result.stepResults().stream()
                .filter(StepResult::succeeded)
                .map(StepResult::stepId)
                .collect(java.util.stream.Collectors.toSet());

        // Index steps by ID for dependency resolution
        Map<String, Step> byId = new HashMap<>();
        for (Step step : plan.getSteps()) {
            byId.put(step.id(), step);
        }

        // Topological sort of all plan steps
        List<String> topoOrder = topologicalSort(plan.getSteps());

        // Reverse and filter to compensable, completed steps only
        List<Step> compensable = new ArrayList<>();
        for (int i = topoOrder.size() - 1; i >= 0; i--) {
            String stepId = topoOrder.get(i);
            Step step = byId.get(stepId);
            if (step != null && step.compensateCapabilityId() != null && succeeded.contains(stepId)) {
                compensable.add(step);
            }
        }
        return compensable;
    }

    private List<String> topologicalSort(List<Step> steps) {
        Map<String, Step> byId = new HashMap<>();
        for (Step step : steps) byId.put(step.id(), step);

        Map<String, Integer> color = new HashMap<>(); // 0=white, 1=grey, 2=black
        List<String> result = new ArrayList<>();

        for (Step step : steps) {
            if (!color.containsKey(step.id())) {
                dfs(step.id(), byId, color, result);
            }
        }
        return result;
    }

    private void dfs(String id, Map<String, Step> byId, Map<String, Integer> color, List<String> result) {
        color.put(id, 1);
        Step step = byId.get(id);
        if (step != null) {
            for (String dep : step.dependsOn()) {
                if (color.getOrDefault(dep, 0) == 0) {
                    dfs(dep, byId, color, result);
                }
            }
        }
        color.put(id, 2);
        result.add(id);
    }
}
