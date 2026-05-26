package com.nexora.core.plan.condition;

import com.nexora.core.context.ExecutionContext;
import com.nexora.core.plan.StepCondition;

import java.util.Objects;

/**
 * Evaluates whether a value resolved from the ExecutionContext matches the expected value.
 * Key uses dot-notation path resolution (e.g., "intent.context.paymentMethod").
 */
public record ContextValueEquals(String key, Object value) implements StepCondition {
    
    public ContextValueEquals {
        Objects.requireNonNull(key, "key must not be null");
    }

    @Override
    public boolean evaluate(ExecutionContext ctx) {
        Object actual = ctx.resolvePath(key);
        return Objects.equals(value, actual);
    }

    @Override
    public String toString() {
        return "ContextValueEquals(" + key + " == " + value + ")";
    }
}
