package me.virul.nexora.core.plan.condition;

import java.util.Objects;
import me.virul.nexora.core.context.ExecutionContext;
import me.virul.nexora.core.plan.StepCondition;

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
