package com.nexora.core.plan.condition;

import com.nexora.core.context.ExecutionContext;
import com.nexora.core.plan.StepCondition;

import java.util.Objects;

/**
 * Evaluates whether an output produced by a prior step matches the expected value.
 */
public record StepOutputEquals(String stepId, String field, Object value) implements StepCondition {

    public StepOutputEquals {
        Objects.requireNonNull(stepId, "stepId must not be null");
    }

    @Override
    public boolean evaluate(ExecutionContext ctx) {
        Object actual = ctx.getStepOutput(stepId, field);
        return Objects.equals(value, actual);
    }

    @Override
    public String toString() {
        String f = field != null ? "." + field : "";
        return "StepOutputEquals(" + stepId + f + " == " + value + ")";
    }
}
