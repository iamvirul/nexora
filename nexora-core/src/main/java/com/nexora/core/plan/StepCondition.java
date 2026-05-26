package com.nexora.core.plan;

import com.nexora.core.context.ExecutionContext;

/**
 * A declarative condition evaluated by the DAG scheduler to determine
 * whether a step should execute. If evaluate() returns false, the step
 * is bypassed and marked as SKIPPED.
 */
@FunctionalInterface
public interface StepCondition {
    
    /**
     * @param ctx the live execution context containing runtime state
     * @return true if the step should execute, false to skip it
     */
    boolean evaluate(ExecutionContext ctx);
}
