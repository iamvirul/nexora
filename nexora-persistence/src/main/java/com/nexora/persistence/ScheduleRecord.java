package com.nexora.persistence;

import com.nexora.core.tenant.TenantId;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/** @param tenantId the tenant every firing runs for; {@link TenantId#DEFAULT} when none was given */
public record ScheduleRecord(
        String id,
        String cronExpression,
        String goal,
        Map<String, Object> context,
        MissedFirePolicy missedFirePolicy,
        Instant createdAt,
        Instant lastFiredAt,
        Instant nextFireAt,
        ScheduleStatus lastStatus,
        boolean active,
        String tenantId) {
    public ScheduleRecord {
        tenantId = TenantId.orDefault(tenantId);
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(cronExpression, "cronExpression");
        Objects.requireNonNull(goal, "goal");
        Objects.requireNonNull(missedFirePolicy, "missedFirePolicy");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(nextFireAt, "nextFireAt");
        if (lastStatus == null) lastStatus = ScheduleStatus.NEVER_RUN;
        context = context == null ? Map.of() : Map.copyOf(context);
    }

    /** A schedule of the {@link TenantId#DEFAULT} tenant. */
    public ScheduleRecord(
            String id,
            String cronExpression,
            String goal,
            Map<String, Object> context,
            MissedFirePolicy missedFirePolicy,
            Instant createdAt,
            Instant lastFiredAt,
            Instant nextFireAt,
            ScheduleStatus lastStatus,
            boolean active) {
        this(
                id,
                cronExpression,
                goal,
                context,
                missedFirePolicy,
                createdAt,
                lastFiredAt,
                nextFireAt,
                lastStatus,
                active,
                null);
    }
}
