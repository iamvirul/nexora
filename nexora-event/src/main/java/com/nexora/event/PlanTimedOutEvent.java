package com.nexora.event;

import java.time.Duration;
import java.time.Instant;

/**
 * Fired when a plan-level deadline expires before all steps complete.
 * Mirrors {@link PlanFailedEvent} in structure but carries the configured
 * {@code deadline} field so observability dashboards can distinguish
 * timeout events by their configured limit.
 *
 * <p>Saga compensation (if enabled) will follow this event.
 */
public record PlanTimedOutEvent(
        String executionId,
        String traceId,
        Duration deadline,
        Duration elapsed,
        Instant occurredAt
) implements ExecutionEvent {}
