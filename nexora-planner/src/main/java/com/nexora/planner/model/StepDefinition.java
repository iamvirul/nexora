package com.nexora.planner.model;

import com.nexora.core.plan.InputBinding;
import com.nexora.core.plan.StepCondition;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
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
    private final StepCondition condition;

    public StepDefinition(String id, String capabilityId, Predicate<String> matcher) {
        this(id, capabilityId, matcher, Map.of(), null, Set.of(), null, null, null, null);
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
        this(id, capabilityId, matcher, inputs, outputKey, dependsOn, retryPolicyId, timeout, null, null);
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
        this(id, capabilityId, matcher, inputs, outputKey, dependsOn, retryPolicyId, timeout, compensateCapabilityId, null);
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
            String compensateCapabilityId,
            StepCondition condition) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.capabilityId = Objects.requireNonNull(capabilityId, "capabilityId must not be null");
        this.matcher = Objects.requireNonNull(matcher, "matcher must not be null");
        this.inputs = inputs == null ? Map.of() : Map.copyOf(inputs);
        this.outputKey = outputKey;
        this.dependsOn = dependsOn == null ? Set.of() : Set.copyOf(dependsOn);
        this.retryPolicyId = retryPolicyId;
        this.timeout = timeout;
        this.compensateCapabilityId = compensateCapabilityId;
        this.condition = condition;
    }

    public String getId() { return id; }
    public String getCapabilityId() { return capabilityId; }
    public Map<String, InputBinding> getInputs() { return inputs; }
    public String getOutputKey() { return outputKey; }
    public Set<String> getDependsOn() { return dependsOn; }
    public String getRetryPolicyId() { return retryPolicyId; }
    public Duration getTimeout() { return timeout; }
    public String getCompensateCapabilityId() { return compensateCapabilityId; }
    public StepCondition getCondition() { return condition; }

    public boolean matches(String goal) {
        return matcher.test(goal);
    }

    public static Builder builder(String id, String capabilityId) {
        return new Builder(id, capabilityId);
    }

    public static class Builder {
        private final String id;
        private final String capabilityId;
        private Predicate<String> matcher = goal -> true;
        private final Map<String, InputBinding> inputs = new HashMap<>();
        private String outputKey;
        private final Set<String> dependsOn = new HashSet<>();
        private String retryPolicyId;
        private Duration timeout;
        private String compensateCapabilityId;
        private StepCondition condition;

        public Builder(String id, String capabilityId) {
            this.id = Objects.requireNonNull(id);
            this.capabilityId = Objects.requireNonNull(capabilityId);
        }

        public Builder withMatcher(Predicate<String> matcher) {
            this.matcher = Objects.requireNonNull(matcher);
            return this;
        }

        public Builder withInput(String key, InputBinding binding) {
            this.inputs.put(key, binding);
            return this;
        }

        public Builder withOutputKey(String outputKey) {
            this.outputKey = outputKey;
            return this;
        }

        public Builder dependsOn(String stepId) {
            this.dependsOn.add(stepId);
            return this;
        }

        public Builder withRetryPolicyId(String retryPolicyId) {
            this.retryPolicyId = retryPolicyId;
            return this;
        }

        public Builder withTimeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public Builder withCompensateCapabilityId(String compensateCapabilityId) {
            this.compensateCapabilityId = compensateCapabilityId;
            return this;
        }

        public Builder withCondition(StepCondition condition) {
            this.condition = condition;
            return this;
        }

        public StepDefinition build() {
            return new StepDefinition(id, capabilityId, matcher, inputs, outputKey, dependsOn, retryPolicyId, timeout, compensateCapabilityId, condition);
        }
    }
}
