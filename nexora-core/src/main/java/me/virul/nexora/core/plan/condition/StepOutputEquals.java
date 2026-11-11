package me.virul.nexora.core.plan.condition;

import java.util.Objects;
import me.virul.nexora.core.context.ExecutionContext;
import me.virul.nexora.core.plan.StepCondition;

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
