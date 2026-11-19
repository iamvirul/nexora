package me.virul.nexora.core.plan.condition;

import java.util.Objects;
import me.virul.nexora.core.context.ExecutionContext;
import me.virul.nexora.core.plan.StepCondition;

/**
 * Evaluates whether an output produced by a prior step matches the expected value.
 *
 * @param stepId the step whose output to read
 * @param field  the key to read from a {@code Map} output, or {@code null} for the whole output
 * @param value  the expected value, compared with {@link Objects#equals(Object, Object)}
 */
public record StepOutputEquals(String stepId, String field, Object value) implements StepCondition {

    /**
     * Validates the condition.
     *
     * @throws NullPointerException if {@code stepId} is null
     */
    public StepOutputEquals {
        Objects.requireNonNull(stepId, "stepId must not be null");
    }

    /** {@inheritDoc} */
    @Override
    public boolean evaluate(ExecutionContext ctx) {
        Object actual = ctx.getStepOutput(stepId, field);
        return Objects.equals(value, actual);
    }

    /**
     * A readable form of the condition, for logs and plan explanations.
     *
     * @return the condition as text
     */
    @Override
    public String toString() {
        String f = field != null ? "." + field : "";
        return "StepOutputEquals(" + stepId + f + " == " + value + ")";
    }
}
