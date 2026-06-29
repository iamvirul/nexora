package com.nexora.event;

import java.time.Instant;

public record ScheduledExecutionFiredEvent(
        String scheduleId,
        String executionId,
        Instant firedAt
) implements ExecutionEvent {}
