package me.virul.nexora.core.plan;

import me.virul.nexora.core.context.ExecutionContext;

/**
 * Describes how a step input value is resolved at execution time.
 * Sealed so every binding strategy is explicit and exhaustively matchable.
 */
public sealed interface InputBinding permits LiteralBinding, ContextBinding, StepOutputBinding {
    Object resolve(ExecutionContext ctx);

    static InputBinding literal(Object value) {
        return new LiteralBinding(value);
    }

    static InputBinding fromContext(String path) {
        return new ContextBinding(path);
    }

    static InputBinding fromStep(String stepId) {
        return new StepOutputBinding(stepId, null);
    }

    static InputBinding fromStep(String stepId, String field) {
        return new StepOutputBinding(stepId, field);
    }
}
