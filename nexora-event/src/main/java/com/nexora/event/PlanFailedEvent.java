package com.nexora.event;

import java.time.Duration;
import java.time.Instant;

public record PlanFailedEvent(
        String executionId,
        String traceId,
        String failedStepId,
        String failureCode,
        Duration elapsed,
        Instant occurredAt)
        implements ExecutionEvent {}
