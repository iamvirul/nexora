package me.virul.nexora.core.plan;

import java.util.Objects;
import me.virul.nexora.core.context.ExecutionContext;

/**
 * Resolves input from a previously completed step's output.
 * When field is null, the entire output object is returned.
 *
 * @param stepId the step whose output to use
 * @param field  the key to read from a {@code Map} output, or {@code null} for the whole output
 */
public record StepOutputBinding(String stepId, String field) implements InputBinding {

    /**
     * Validates the binding.
     *
     * @throws NullPointerException if {@code stepId} is null
     */
    public StepOutputBinding {
        Objects.requireNonNull(stepId, "stepId must not be null");
    }

    /** {@inheritDoc} */
    @Override
    public Object resolve(ExecutionContext ctx) {
        return ctx.getStepOutput(stepId, field);
    }
}
