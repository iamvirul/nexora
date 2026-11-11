package me.virul.nexora.event;

import java.time.Duration;
import java.time.Instant;

public record PlanCompletedEvent(String executionId, String traceId, Duration elapsed, Instant occurredAt)
        implements ExecutionEvent {}
