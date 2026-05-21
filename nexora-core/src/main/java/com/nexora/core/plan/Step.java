package com.nexora.core.plan;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record Step(
        String id,
        String capabilityId,
        Map<String, InputBinding> inputs,
        String outputKey,                  // where to write result in ExecutionContext; may be null
        Set<String> dependsOn,             // step IDs that must complete before this step runs
        String retryPolicyId,              // resolved from RetryPolicyRegistry; null = engine default
        Duration timeout,                  // per-step timeout; null = engine default
        String compensateCapabilityId      // capability to invoke on saga rollback; null = no compensation
) {
    public Step {
        Objects.requireNonNull(id, "Step id must not be null");
        Objects.requireNonNull(capabilityId, "capabilityId must not be null");
        inputs = inputs == null ? Map.of() : Map.copyOf(inputs);
        dependsOn = dependsOn == null ? Set.of() : Set.copyOf(dependsOn);
    }

    /** Convenience factory for steps with no inputs, no dependencies, and no compensation. */
    public static Step of(String id, String capabilityId) {
        return new Step(id, capabilityId, Map.of(), null, Set.of(), null, null, null);
    }
}
