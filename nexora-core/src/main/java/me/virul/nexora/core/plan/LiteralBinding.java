package me.virul.nexora.core.plan;

import me.virul.nexora.core.context.ExecutionContext;

public record LiteralBinding(Object value) implements InputBinding {
    @Override
    public Object resolve(ExecutionContext ctx) {
        return value;
    }
}
