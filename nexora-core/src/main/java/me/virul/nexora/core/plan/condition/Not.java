package me.virul.nexora.core.plan.condition;

import java.util.Objects;
import me.virul.nexora.core.context.ExecutionContext;
import me.virul.nexora.core.plan.StepCondition;

/**
 * Logical NOT condition.
 *
 * @param condition the condition to negate
 */
public record Not(StepCondition condition) implements StepCondition {

    /**
     * Validates the condition.
     *
     * @throws NullPointerException if {@code condition} is null
     */
    public Not {
        Objects.requireNonNull(condition, "condition must not be null");
    }

    /** {@inheritDoc} */
    @Override
    public boolean evaluate(ExecutionContext ctx) {
        return !condition.evaluate(ctx);
    }

    /**
     * A readable form of the condition, for logs and plan explanations.
     *
     * @return the condition as text
     */
    @Override
    public String toString() {
        return "Not(" + condition + ")";
    }
}
