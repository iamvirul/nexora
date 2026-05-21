package com.nexora.event;

import java.time.Duration;
import java.time.Instant;

public record CompensationFailedEvent(
        String executionId,
        String traceId,
        String stepId,
        String compensateCapabilityId,
        String failureCode,
        String failureMessage,
        Duration elapsed,
        Instant occurredAt
) implements ExecutionEvent {}
