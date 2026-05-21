package com.nexora.planner.model;

import com.nexora.core.plan.InputBinding;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

public class StepDefinition {

    private final String id;
    private final String capabilityId;
    private final Predicate<String> matcher;
    private final Map<String, InputBinding> inputs;
    private final String outputKey;
    private final Set<String> dependsOn;
    private final String retryPolicyId;
    private final Duration timeout;
    private final String compensateCapabilityId;

    public StepDefinition(String id, String capabilityId, Predicate<String> matcher) {
        this(id, capabilityId, matcher, Map.of(), null, Set.of(), null, null, null);
    }

    public StepDefinition(
            String id,
            String capabilityId,
            Predicate<String> matcher,
            Map<String, InputBinding> inputs,
            String outputKey,
            Set<String> dependsOn,
            String retryPolicyId,
            Duration timeout) {
        this(id, capabilityId, matcher, inputs, outputKey, dependsOn, retryPolicyId, timeout, null);
    }

    public StepDefinition(
            String id,
            String capabilityId,
            Predicate<String> matcher,
            Map<String, InputBinding> inputs,
            String outputKey,
            Set<String> dependsOn,
            String retryPolicyId,
            Duration timeout,
            String compensateCapabilityId) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.capabilityId = Objects.requireNonNull(capabilityId, "capabilityId must not be null");
        this.matcher = Objects.requireNonNull(matcher, "matcher must not be null");
        this.inputs = inputs == null ? Map.of() : Map.copyOf(inputs);
        this.outputKey = outputKey;
        this.dependsOn = dependsOn == null ? Set.of() : Set.copyOf(dependsOn);
        this.retryPolicyId = retryPolicyId;
        this.timeout = timeout;
        this.compensateCapabilityId = compensateCapabilityId;
    }

    public String getId() { return id; }
    public String getCapabilityId() { return capabilityId; }
    public Map<String, InputBinding> getInputs() { return inputs; }
    public String getOutputKey() { return outputKey; }
    public Set<String> getDependsOn() { return dependsOn; }
    public String getRetryPolicyId() { return retryPolicyId; }
    public Duration getTimeout() { return timeout; }
    public String getCompensateCapabilityId() { return compensateCapabilityId; }

    public boolean matches(String goal) {
        return matcher.test(goal);
    }
}
