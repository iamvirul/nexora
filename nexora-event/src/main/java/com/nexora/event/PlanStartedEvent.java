package com.nexora.event;

import java.time.Instant;

public record PlanStartedEvent(String executionId, String traceId, Instant occurredAt) implements ExecutionEvent {}
