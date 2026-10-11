package com.nexora.api;

import com.nexora.persistence.ExecutionState;

/** The execution exists but its state does not allow a replay (it succeeded, or is still running or compensating). */
public final class ExecutionNotReplayableException extends RuntimeException {

    private final String executionId;
    private final ExecutionState state;

    public ExecutionNotReplayableException(String executionId, ExecutionState state) {
        super("Execution " + executionId + " is " + state
                + "; only FAILED, TIMED_OUT, CANCELLED and COMPENSATED executions can be replayed");
        this.executionId = executionId;
        this.state = state;
    }

    public String executionId() {
        return executionId;
    }

    public ExecutionState state() {
        return state;
    }
}
