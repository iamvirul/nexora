package com.nexora.core.capability;

import com.nexora.core.context.TraceContext;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

public record CapabilityRequest(
        String capabilityId,
        String stepId,
        String idempotencyKey,     // unique per step-execution attempt; use for dedup in capabilities
        Map<String, Object> inputs,
        TraceContext traceContext,
        Duration timeout           // null means use engine default
) {
    public CapabilityRequest {
        Objects.requireNonNull(capabilityId, "capabilityId must not be null");
        Objects.requireNonNull(stepId, "stepId must not be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        Objects.requireNonNull(traceContext, "traceContext must not be null");
        inputs = inputs == null ? Map.of() : Map.copyOf(inputs);
    }
}
