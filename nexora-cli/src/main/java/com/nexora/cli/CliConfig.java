package com.nexora.cli;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.nexora.core.plan.StepCondition;
import com.nexora.core.plan.condition.And;
import com.nexora.core.plan.condition.ContextValueEquals;
import com.nexora.core.plan.condition.Not;
import com.nexora.core.plan.condition.Or;
import com.nexora.core.plan.condition.StepOutputEquals;
import com.nexora.core.plan.condition.StepOutputPresent;

import java.util.List;
import java.util.stream.Collectors;

/** Deserialize from nexora.json in the working directory, or from --config. */
public class CliConfig {

    public List<StepConfig> steps = List.of();
    public RetryConfig retry = new RetryConfig();
    public String webhookSecret;
    /** File path for the H2 embedded store (e.g. "./nexora-data"). Omit for no persistence. */
    public String executionStore;

    public static class StepConfig {
        public String id;
        public String capabilityId;
        public String matchesGoalContains; // simple: matches if goal contains this string
        public ConditionConfig condition;
    }

    public static class RetryConfig {
        public int maxAttempts = 3;
        public long initialDelayMs = 200;
        public double multiplier = 2.0;
        public long maxDelayMs = 10_000;
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = ContextValueEqualsConfig.class, name = "contextValueEquals"),
            @JsonSubTypes.Type(value = StepOutputEqualsConfig.class, name = "stepOutputEquals"),
            @JsonSubTypes.Type(value = StepOutputPresentConfig.class, name = "stepOutputPresent"),
            @JsonSubTypes.Type(value = AndConfig.class, name = "and"),
            @JsonSubTypes.Type(value = OrConfig.class, name = "or"),
            @JsonSubTypes.Type(value = NotConfig.class, name = "not")
    })
    public interface ConditionConfig {
        StepCondition toStepCondition();
    }

    public static class ContextValueEqualsConfig implements ConditionConfig {
        public String key;
        public Object value;
        @Override public StepCondition toStepCondition() { return new ContextValueEquals(key, value); }
    }

    public static class StepOutputEqualsConfig implements ConditionConfig {
        public String stepId;
        public String field;
        public Object value;
        @Override public StepCondition toStepCondition() { return new StepOutputEquals(stepId, field, value); }
    }

    public static class StepOutputPresentConfig implements ConditionConfig {
        public String stepId;
        public String field;
        @Override public StepCondition toStepCondition() { return new StepOutputPresent(stepId, field); }
    }

    public static class AndConfig implements ConditionConfig {
        public List<ConditionConfig> conditions;
        @Override public StepCondition toStepCondition() {
            if (conditions == null) {
                throw new IllegalStateException("AndConfig: 'conditions' list must not be null");
            }
            if (conditions.stream().anyMatch(c -> c == null)) {
                throw new IllegalStateException("AndConfig: 'conditions' list must not contain null elements");
            }
            return new And(conditions.stream().map(ConditionConfig::toStepCondition).collect(Collectors.toList()));
        }
    }

    public static class OrConfig implements ConditionConfig {
        public List<ConditionConfig> conditions;
        @Override public StepCondition toStepCondition() {
            if (conditions == null) {
                throw new IllegalStateException("OrConfig: 'conditions' list must not be null");
            }
            if (conditions.stream().anyMatch(c -> c == null)) {
                throw new IllegalStateException("OrConfig: 'conditions' list must not contain null elements");
            }
            return new Or(conditions.stream().map(ConditionConfig::toStepCondition).collect(Collectors.toList()));
        }
    }

    public static class NotConfig implements ConditionConfig {
        public ConditionConfig condition;
        @Override public StepCondition toStepCondition() {
            if (condition == null) {
                throw new IllegalStateException("NotConfig: 'condition' must not be null");
            }
            return new Not(condition.toStepCondition());
        }
    }
}
