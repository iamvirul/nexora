package com.nexora.core.plan.condition;

import com.nexora.core.context.ExecutionContext;
import com.nexora.core.plan.StepCondition;

import java.util.Objects;

/**
 * Evaluates whether an output produced by a prior step is present (non-null).
 */
public record StepOutputPresent(String stepId, String field) implements StepCondition {

    public StepOutputPresent {
        Objects.requireNonNull(stepId, "stepId must not be null");
    }

    @Override
    public boolean evaluate(ExecutionContext ctx) {
        return ctx.getStepOutput(stepId, field) != null;
    }

    @Override
    public String toString() {
        String f = field != null ? "." + field : "";
        return "StepOutputPresent(" + stepId + f + ")";
    }
}
