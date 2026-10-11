package com.nexora.event;

import java.time.Duration;
import java.time.Instant;

public record StepCompletedEvent(
        String executionId, String stepId, String capabilityId, String traceId, Duration elapsed, Instant occurredAt)
        implements ExecutionEvent {}
