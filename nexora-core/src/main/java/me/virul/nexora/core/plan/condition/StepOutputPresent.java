package me.virul.nexora.core.plan.condition;

import java.util.Objects;
import me.virul.nexora.core.context.ExecutionContext;
import me.virul.nexora.core.plan.StepCondition;

/**
 * Evaluates whether an output produced by a prior step is present (non-null).
 *
 * @param stepId the step whose output to read
 * @param field  the key to read from a {@code Map} output, or {@code null} for the whole output
 */
public record StepOutputPresent(String stepId, String field) implements StepCondition {

    /**
     * Validates the condition.
     *
     * @throws NullPointerException if {@code stepId} is null
     */
    public StepOutputPresent {
        Objects.requireNonNull(stepId, "stepId must not be null");
    }

    /** {@inheritDoc} */
    @Override
    public boolean evaluate(ExecutionContext ctx) {
        return ctx.getStepOutput(stepId, field) != null;
    }

    /**
     * A readable form of the condition, for logs and plan explanations.
     *
     * @return the condition as text
     */
    @Override
    public String toString() {
        String f = field != null ? "." + field : "";
        return "StepOutputPresent(" + stepId + f + ")";
    }
}
