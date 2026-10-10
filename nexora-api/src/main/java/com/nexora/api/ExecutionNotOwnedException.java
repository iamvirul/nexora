package com.nexora.api;

/**
 * The execution is recorded as RUNNING in the store but is not running on this engine, so this
 * engine cannot stop it. Either another engine instance owns it, or a previous process died
 * mid-execution and left the record behind.
 */
public final class ExecutionNotOwnedException extends RuntimeException {

    private final String executionId;

    public ExecutionNotOwnedException(String executionId) {
        super("Execution " + executionId + " is RUNNING in the store but not on this engine instance");
        this.executionId = executionId;
    }

    public String executionId() {
        return executionId;
    }
}
