package me.virul.nexora.event;

import java.time.Instant;

public record StepStartedEvent(
        String executionId,
        String stepId,
        String capabilityId,
        String idempotencyKey,
        String traceId,
        String spanId,
        Instant occurredAt)
        implements ExecutionEvent {}
