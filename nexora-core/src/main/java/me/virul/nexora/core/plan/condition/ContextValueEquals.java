package me.virul.nexora.core.plan.condition;

import java.util.Objects;
import me.virul.nexora.core.context.ExecutionContext;
import me.virul.nexora.core.plan.StepCondition;

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
