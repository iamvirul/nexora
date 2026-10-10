package com.nexora.runtime.engine;

/** Result of {@link ExecutionEngine#cancel(String)}. */
public enum CancellationOutcome {
    /** The execution was running here and is now stopping; it will finish as CANCELLED. Repeat cancels also return this. */
    CANCELLED,
    /** The execution already finished, or is already stopping for another reason (deadline expired). */
    ALREADY_TERMINAL,
    /** No execution with this id is known to this engine or its store. */
    NOT_FOUND,
    /**
     * The store has the execution as RUNNING but this engine is not running it: another engine
     * instance owns it, or a previous process died mid-execution.
     */
    NOT_RUNNING_ON_THIS_ENGINE
}
