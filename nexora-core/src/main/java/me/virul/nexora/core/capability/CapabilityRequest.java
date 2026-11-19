package me.virul.nexora.core.capability;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import me.virul.nexora.core.context.TraceContext;
import me.virul.nexora.core.tenant.TenantId;

/**
 * One call of a capability for one attempt of a plan step.
 *
 * @param capabilityId   the capability to invoke
 * @param stepId         the plan step this call belongs to
 * @param idempotencyKey the same across retries of a step, and reused when a replay re-runs a step
 *                       that completed; capabilities with side effects use it to deduplicate
 * @param inputs         the resolved step inputs; {@code null} values are dropped, so test an absent
 *                       flag with {@code getOrDefault} or {@code Boolean.TRUE.equals}. Never null and
 *                       unmodifiable; a shallow copy, so mutable values are shared, not copied
 * @param traceContext   the span this call runs in
 * @param timeout        the time limit for this call, or {@code null} to use the engine default
 * @param executionId    the execution this call belongs to
 * @param attemptNumber  {@code 0} for the first attempt, incremented for each retry
 * @param tenantId       the tenant the execution belongs to; {@code null} becomes
 *                       {@link TenantId#DEFAULT}, so it is never null
 */
public record CapabilityRequest(
        String capabilityId,
        String stepId,
        String idempotencyKey,
        Map<String, Object> inputs,
        TraceContext traceContext,
        Duration timeout,
        String executionId,
        int attemptNumber,
        String tenantId) {

    /**
     * Validates the request and copies its inputs.
     *
     * @throws NullPointerException     if {@code capabilityId}, {@code stepId}, {@code idempotencyKey},
     *                                  {@code traceContext} or {@code executionId} is null
     * @throws IllegalArgumentException if {@code tenantId} is not a valid tenant id
     */
    public CapabilityRequest {
        tenantId = TenantId.orDefault(tenantId);
        Objects.requireNonNull(capabilityId, "capabilityId must not be null");
        Objects.requireNonNull(stepId, "stepId must not be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        Objects.requireNonNull(traceContext, "traceContext must not be null");
        Objects.requireNonNull(executionId, "executionId must not be null");
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

    /**
     * A request for the {@link TenantId#DEFAULT} tenant, as before requests carried one.
     *
     * @param capabilityId   the capability to invoke
     * @param stepId         the plan step this call belongs to
     * @param idempotencyKey the deduplication key of the step
     * @param inputs         the resolved step inputs
     * @param traceContext   the span this call runs in
     * @param timeout        the time limit for this call, or {@code null} for the engine default
     * @param executionId    the execution this call belongs to
     * @param attemptNumber  {@code 0} for the first attempt
     */
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

    /**
     * Returns a copy of this request with a different attempt number.
     *
     * @param attempt the attempt number of the copy
     * @return the copy; every other component is unchanged
     */
    public CapabilityRequest withAttempt(int attempt) {
        return new CapabilityRequest(
                capabilityId, stepId, idempotencyKey, inputs, traceContext, timeout, executionId, attempt, tenantId);
    }
}
