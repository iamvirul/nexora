package me.virul.nexora.core.plan;

import java.util.Objects;
import me.virul.nexora.core.context.ExecutionContext;

public record ContextBinding(String path) implements InputBinding {
    public ContextBinding {
        Objects.requireNonNull(path, "path must not be null");
    }

    @Override
    public Object resolve(ExecutionContext ctx) {
        return ctx.resolvePath(path);
    }
}
