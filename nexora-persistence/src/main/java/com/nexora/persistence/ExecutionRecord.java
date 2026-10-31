package com.nexora.persistence;

import com.nexora.core.tenant.TenantId;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** @param tenantId the tenant the execution belongs to; {@link TenantId#DEFAULT} when none was given */
public record ExecutionRecord(
        String executionId,
        String traceId,
        String goal,
        Map<String, Object> context,
        ExecutionState state,
        Instant startedAt,
        Instant completedAt,
        List<StepRecord> steps,
        String tenantId) {
    public ExecutionRecord {
        tenantId = TenantId.orDefault(tenantId);
        Objects.requireNonNull(executionId);
        Objects.requireNonNull(state);
        Objects.requireNonNull(startedAt);
        context = context == null ? Map.of() : Map.copyOf(context);
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    /** An execution of the {@link TenantId#DEFAULT} tenant. */
    public ExecutionRecord(
            String executionId,
            String traceId,
            String goal,
            Map<String, Object> context,
            ExecutionState state,
            Instant startedAt,
            Instant completedAt,
            List<StepRecord> steps) {
        this(executionId, traceId, goal, context, state, startedAt, completedAt, steps, null);
    }

    public static ExecutionRecord started(
            String executionId, String traceId, String goal, Map<String, Object> context, Instant startedAt) {
        return started(executionId, traceId, goal, context, startedAt, null);
    }

    public static ExecutionRecord started(
            String executionId,
            String traceId,
            String goal,
            Map<String, Object> context,
            Instant startedAt,
            String tenantId) {
        return new ExecutionRecord(
                executionId, traceId, goal, context, ExecutionState.RUNNING, startedAt, null, List.of(), tenantId);
    }

    /** A copy with {@code newSteps}; every other field, the tenant included, is kept. */
    public ExecutionRecord withSteps(List<StepRecord> newSteps) {
        return new ExecutionRecord(
                executionId, traceId, goal, context, state, startedAt, completedAt, newSteps, tenantId);
    }
}
