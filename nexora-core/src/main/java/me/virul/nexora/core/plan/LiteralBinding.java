package me.virul.nexora.core.plan;

import me.virul.nexora.core.context.ExecutionContext;

/**
 * An input with a fixed value.
 *
 * @param value the value; {@code null} leaves the input out of the request
 */
public record LiteralBinding(Object value) implements InputBinding {
    /** {@inheritDoc} */
    @Override
    public Object resolve(ExecutionContext ctx) {
        return value;
    }
}
