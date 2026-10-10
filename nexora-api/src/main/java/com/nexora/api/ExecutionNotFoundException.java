package com.nexora.api;

/** No execution with the given id is known to this engine or its persistence store. */
public final class ExecutionNotFoundException extends RuntimeException {

    private final String executionId;

    public ExecutionNotFoundException(String executionId) {
        super("No execution found with id: " + executionId);
        this.executionId = executionId;
    }

    public String executionId() {
        return executionId;
    }
}
