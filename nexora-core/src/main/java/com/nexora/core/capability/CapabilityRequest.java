package com.nexora.core.capability;

import com.nexora.core.context.TraceContext;
import com.nexora.core.tenant.TenantId;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

public record CapabilityRequest(
        String capabilityId,
        String stepId,
        String idempotencyKey, // same across retries of a step, and reused when a replay re-runs a step that
        // completed; use for dedup
        Map<String, Object> inputs,
        TraceContext traceContext,
        Duration timeout, // null means use engine default
        String executionId,
        int attemptNumber, // 0 for the first attempt; incremented by RetryInterceptor
        String tenantId // the tenant the execution belongs to; never null
        ) {
    public CapabilityRequest {
        tenantId = TenantId.orDefault(tenantId);
        Objects.requireNonNull(capabilityId, "capabilityId must not be null");
        Objects.requireNonNull(stepId, "stepId must not be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        Objects.requireNonNull(traceContext, "traceContext must not be null");
        Objects.requireNonNull(executionId, "executionId must not be null");
        // Optional context bindings resolve to null when the caller omits them.
        // Map.copyOf() rejects null values, so strip them before copying.
        // Capabilities should use getOrDefault() or Boolean.TRUE.equals() to test absent flags.
        if (inputs == null) {
            inputs = Map.of();
        } else {
            Map<String, Object> filtered = new java.util.HashMap<>(inputs.size());
            inputs.forEach((k, v) -> {
                if (v != null) filtered.put(k, v);
            });
            inputs = Map.copyOf(filtered);
        }
    }

    /** A request for the {@link TenantId#DEFAULT} tenant, as before requests carried one. */
    public CapabilityRequest(
            String capabilityId,
            String stepId,
            String idempotencyKey,
            Map<String, Object> inputs,
            TraceContext traceContext,
            Duration timeout,
            String executionId,
            int attemptNumber) {
        this(capabilityId, stepId, idempotencyKey, inputs, traceContext, timeout, executionId, attemptNumber, null);
    }

    /** Returns a copy of this request with a different attempt number. */
    public CapabilityRequest withAttempt(int attempt) {
        return new CapabilityRequest(
                capabilityId, stepId, idempotencyKey, inputs, traceContext, timeout, executionId, attempt, tenantId);
    }
}
