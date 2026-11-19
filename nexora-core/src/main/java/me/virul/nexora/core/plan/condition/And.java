package me.virul.nexora.core.plan.condition;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import me.virul.nexora.core.context.ExecutionContext;
import me.virul.nexora.core.plan.StepCondition;

/**
 * Logical AND condition: true when every condition is true, and for an empty list.
 *
 * @param conditions the conditions, evaluated in order until one is false; never null and immutable
 */
public record And(List<StepCondition> conditions) implements StepCondition {

    /**
     * Validates the condition.
     *
     * @throws NullPointerException if {@code conditions} is null
     */
    public And {
        conditions = List.copyOf(Objects.requireNonNull(conditions, "conditions must not be null"));
    }

    /** {@inheritDoc} */
    @Override
    public boolean evaluate(ExecutionContext ctx) {
        return conditions.stream().allMatch(c -> c.evaluate(ctx));
    }

    /**
     * A readable form of the condition, for logs and plan explanations.
     *
     * @return the condition as text
     */
    @Override
    public String toString() {
        return "And(" + conditions.stream().map(Object::toString).collect(Collectors.joining(", ")) + ")";
    }
}
