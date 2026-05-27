package com.nexora.core.plan.condition;

import com.nexora.core.context.ExecutionContext;
import com.nexora.core.plan.StepCondition;

import java.util.Objects;

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
