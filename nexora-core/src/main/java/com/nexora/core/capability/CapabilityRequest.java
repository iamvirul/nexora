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
        Duration timeout,          // null means use engine default
        String executionId,
        int attemptNumber          // 0 for the first attempt; incremented by RetryInterceptor
) {
    public CapabilityRequest {
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
            inputs.forEach((k, v) -> { if (v != null) filtered.put(k, v); });
            inputs = Map.copyOf(filtered);
        }
    }

    /** Returns a copy of this request with a different attempt number. */
    public CapabilityRequest withAttempt(int attempt) {
        return new CapabilityRequest(
                capabilityId, stepId, idempotencyKey, inputs, traceContext, timeout, executionId, attempt);
    }
}
