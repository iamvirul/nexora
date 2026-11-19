package me.virul.nexora.core.plan.condition;

import java.util.Objects;
import me.virul.nexora.core.context.ExecutionContext;
import me.virul.nexora.core.plan.StepCondition;

/**
 * Evaluates whether a value resolved from the ExecutionContext matches the expected value.
 * Key uses dot-notation path resolution (e.g., "intent.context.paymentMethod").
 *
 * @param key   the path to resolve, see {@link ExecutionContext#resolvePath(String)}
 * @param value the expected value, compared with {@link Objects#equals(Object, Object)}; {@code null}
 *              matches a path that resolves to nothing
 */
public record ContextValueEquals(String key, Object value) implements StepCondition {

    /**
     * Validates the condition.
     *
     * @throws NullPointerException if {@code key} is null
     */
    public ContextValueEquals {
        Objects.requireNonNull(key, "key must not be null");
    }

    /** {@inheritDoc} */
    @Override
    public boolean evaluate(ExecutionContext ctx) {
        Object actual = ctx.resolvePath(key);
        return Objects.equals(value, actual);
    }

    /**
     * A readable form of the condition, for logs and plan explanations.
     *
     * @return the condition as text
     */
    @Override
    public String toString() {
        return "ContextValueEquals(" + key + " == " + value + ")";
    }
}
