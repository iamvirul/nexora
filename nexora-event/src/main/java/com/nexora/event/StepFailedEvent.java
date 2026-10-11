package com.nexora.event;

import java.time.Duration;
import java.time.Instant;

public record StepFailedEvent(
        String executionId,
        String stepId,
        String capabilityId,
        String traceId,
        String failureCode,
        String failureMessage,
        Duration elapsed,
        Instant occurredAt)
        implements ExecutionEvent {}
