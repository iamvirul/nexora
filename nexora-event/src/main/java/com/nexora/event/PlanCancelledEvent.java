package com.nexora.event;

import java.time.Duration;
import java.time.Instant;

/**
 * Fired when an execution stops because it was cancelled via {@code NexoraEngine.cancel(executionId)}.
 * Published once the in-flight steps have drained, so {@code elapsed} covers the whole run.
 *
 * <p>Saga compensation (if enabled) will follow this event.
 */
public record PlanCancelledEvent(
        String executionId,
        String traceId,
        Duration elapsed,
        Instant occurredAt
) implements ExecutionEvent {}
