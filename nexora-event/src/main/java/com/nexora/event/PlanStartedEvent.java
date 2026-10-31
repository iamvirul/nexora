package com.nexora.event;

import com.nexora.core.tenant.TenantId;
import java.time.Instant;

/** @param tenantId the tenant the execution belongs to; never null */
public record PlanStartedEvent(String executionId, String traceId, Instant occurredAt, String tenantId)
        implements ExecutionEvent {

    public PlanStartedEvent {
        tenantId = TenantId.orDefault(tenantId);
    }

    /** An execution of the {@link TenantId#DEFAULT} tenant. */
    public PlanStartedEvent(String executionId, String traceId, Instant occurredAt) {
        this(executionId, traceId, occurredAt, null);
    }
}
