package com.nexora.event;

import java.time.Instant;

public record ScheduledExecutionMissedEvent(
        String scheduleId,
        int missedCount,
        Instant windowStart,
        Instant windowEnd
) implements ExecutionEvent {}
