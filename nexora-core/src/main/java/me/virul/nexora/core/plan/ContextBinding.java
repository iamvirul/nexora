package me.virul.nexora.core.plan;

import java.util.Objects;
import me.virul.nexora.core.context.ExecutionContext;

/**
 * Resolves an input from the execution context by a dotted path, as
 * {@link ExecutionContext#resolvePath(String)} does.
 *
 * @param path the path, such as {@code intent.context.userId}
 */
public record ContextBinding(String path) implements InputBinding {

    /**
     * Validates the binding.
     *
     * @throws NullPointerException if {@code path} is null
     */
    public ContextBinding {
        Objects.requireNonNull(path, "path must not be null");
    }

    /** {@inheritDoc} */
    @Override
    public Object resolve(ExecutionContext ctx) {
        return ctx.resolvePath(path);
    }
}
