package com.nexora.core.plan.condition;

import com.nexora.core.context.ExecutionContext;
import com.nexora.core.plan.StepCondition;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Logical AND condition.
 */
public record And(List<StepCondition> conditions) implements StepCondition {

    public And {
        conditions = List.copyOf(Objects.requireNonNull(conditions, "conditions must not be null"));
    }

    @Override
    public boolean evaluate(ExecutionContext ctx) {
        return conditions.stream().allMatch(c -> c.evaluate(ctx));
    }

    @Override
    public String toString() {
        return "And(" + conditions.stream().map(Object::toString).collect(Collectors.joining(", ")) + ")";
    }
}
