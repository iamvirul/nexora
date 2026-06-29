package com.nexora.persistence;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public record ScheduleRecord(
        String id,
        String cronExpression,
        String goal,
        Map<String, Object> context,
        MissedFirePolicy missedFirePolicy,
        Instant createdAt,
        Instant lastFiredAt,
        Instant nextFireAt,
        boolean active
) {
    public ScheduleRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(cronExpression, "cronExpression");
        Objects.requireNonNull(goal, "goal");
        Objects.requireNonNull(missedFirePolicy, "missedFirePolicy");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(nextFireAt, "nextFireAt");
        context = context == null ? Map.of() : Map.copyOf(context);
    }
}
