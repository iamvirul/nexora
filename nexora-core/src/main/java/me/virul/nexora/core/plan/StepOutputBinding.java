package me.virul.nexora.core.plan;

import java.util.Objects;
import me.virul.nexora.core.context.ExecutionContext;

/**
 * Resolves input from a previously completed step's output.
 * When field is null, the entire output object is returned.
 */
public record StepOutputBinding(String stepId, String field) implements InputBinding {
    public StepOutputBinding {
        Objects.requireNonNull(stepId, "stepId must not be null");
    }

    @Override
    public Object resolve(ExecutionContext ctx) {
        return ctx.getStepOutput(stepId, field);
    }
}
