package com.nexora.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record ExecutionRecord(
        String executionId,
        String traceId,
        String goal,
        Map<String, Object> context,
        ExecutionState state,
        Instant startedAt,
        Instant completedAt,
        List<StepRecord> steps) {
    public ExecutionRecord {
        Objects.requireNonNull(executionId);
        Objects.requireNonNull(state);
        Objects.requireNonNull(startedAt);
        context = context == null ? Map.of() : Map.copyOf(context);
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    public static ExecutionRecord started(
            String executionId, String traceId, String goal, Map<String, Object> context, Instant startedAt) {
        return new ExecutionRecord(
                executionId, traceId, goal, context, ExecutionState.RUNNING, startedAt, null, List.of());
    }
}
