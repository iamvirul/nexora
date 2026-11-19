package me.virul.nexora.core.plan;

import me.virul.nexora.core.context.ExecutionContext;

/**
 * Describes how a step input value is resolved at execution time.
 * Sealed so every binding strategy is explicit and exhaustively matchable.
 */
public sealed interface InputBinding permits LiteralBinding, ContextBinding, StepOutputBinding {
    /**
     * Resolves the input value just before the step runs.
     *
     * @param ctx the live execution context
     * @return the value, or {@code null} when there is none, in which case the input is left out of
     *         the request
     */
    Object resolve(ExecutionContext ctx);

    /**
     * An input with a fixed value.
     *
     * @param value the value
     * @return a {@link LiteralBinding}
     */
    static InputBinding literal(Object value) {
        return new LiteralBinding(value);
    }

    /**
     * An input read from the execution context.
     *
     * @param path a dotted path, see {@link ExecutionContext#resolvePath(String)}
     * @return a {@link ContextBinding}
     */
    static InputBinding fromContext(String path) {
        return new ContextBinding(path);
    }

    /**
     * An input set to the whole output of an earlier step.
     *
     * @param stepId the step whose output to use
     * @return a {@link StepOutputBinding}
     */
    static InputBinding fromStep(String stepId) {
        return new StepOutputBinding(stepId, null);
    }

    /**
     * An input set to one field of the {@code Map} output of an earlier step.
     *
     * @param stepId the step whose output to use
     * @param field  the key to read from its output, or {@code null} for the whole output
     * @return a {@link StepOutputBinding}
     */
    static InputBinding fromStep(String stepId, String field) {
        return new StepOutputBinding(stepId, field);
    }
}
