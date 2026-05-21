package com.nexora.event;

import java.time.Duration;
import java.time.Instant;

public record CompensationCompletedEvent(
        String executionId,
        String traceId,
        String stepId,
        String compensateCapabilityId,
        Duration elapsed,
        Instant occurredAt
) implements ExecutionEvent {}
