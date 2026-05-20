package com.nexora.event;

import java.time.Instant;

public record PlanAmendedEvent(
        String executionId,
        String traceId,
        String amendmentType,
        String targetStepId,
        Instant timestamp
) implements ExecutionEvent {
}
