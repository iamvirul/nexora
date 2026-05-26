package com.nexora.core.plan.condition;

import com.nexora.core.context.ExecutionContext;
import com.nexora.core.plan.StepCondition;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Logical OR condition.
 */
public record Or(List<StepCondition> conditions) implements StepCondition {

    public Or {
        conditions = List.copyOf(Objects.requireNonNull(conditions, "conditions must not be null"));
    }

    @Override
    public boolean evaluate(ExecutionContext ctx) {
        return conditions.stream().anyMatch(c -> c.evaluate(ctx));
    }

    @Override
    public String toString() {
        return "Or(" + conditions.stream().map(Object::toString).collect(Collectors.joining(", ")) + ")";
    }
}
