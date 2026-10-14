package com.nexora.event;

import java.time.Duration;
import java.time.Instant;

/**
 * A step finished successfully.
 *
 * @param output what the capability returned, exactly as returned and not size-limited; may be
 *               {@code null}. Subscribers that keep or send it should cap it first, as the store
 *               and the process snapshot do.
 */
public record StepCompletedEvent(
        String executionId,
        String stepId,
        String capabilityId,
        String traceId,
        Duration elapsed,
        Instant occurredAt,
        Object output)
        implements ExecutionEvent {

    /** A completion without output, as published before outputs were carried. */
    public StepCompletedEvent(
            String executionId,
            String stepId,
            String capabilityId,
            String traceId,
            Duration elapsed,
            Instant occurredAt) {
        this(executionId, stepId, capabilityId, traceId, elapsed, occurredAt, null);
    }
}
