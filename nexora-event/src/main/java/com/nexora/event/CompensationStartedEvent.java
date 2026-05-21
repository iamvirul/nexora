package com.nexora.event;

import java.time.Instant;

public record CompensationStartedEvent(
        String executionId,
        String traceId,
        String stepId,
        String compensateCapabilityId,
        Instant occurredAt
) implements ExecutionEvent {}
