package me.virul.nexora.core.plan.condition;

import java.util.Objects;
import me.virul.nexora.core.context.ExecutionContext;
import me.virul.nexora.core.plan.StepCondition;

/**
 * Logical NOT condition.
 */
public record Not(StepCondition condition) implements StepCondition {

    public Not {
        Objects.requireNonNull(condition, "condition must not be null");
    }

    @Override
    public boolean evaluate(ExecutionContext ctx) {
        return !condition.evaluate(ctx);
    }

    @Override
    public String toString() {
        return "Not(" + condition + ")";
    }
}
