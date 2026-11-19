package me.virul.nexora.api;

import me.virul.nexora.persistence.ExecutionState;

/** The execution exists but its state does not allow a replay (it succeeded, or is still running or compensating). */
public final class ExecutionNotReplayableException extends RuntimeException {

    /** The execution that was asked to be replayed. */
    private final String executionId;

    /** Its state when the replay was refused. */
    private final ExecutionState state;

    /**
     * Creates the exception.
     *
     * @param executionId the execution that was asked to be replayed
     * @param state       its state, which does not allow a replay
     */
    public ExecutionNotReplayableException(String executionId, ExecutionState state) {
        super("Execution " + executionId + " is " + state
                + "; only FAILED, TIMED_OUT, CANCELLED and COMPENSATED executions can be replayed");
        this.executionId = executionId;
        this.state = state;
    }

    /**
     * The execution that was asked to be replayed.
     *
     * @return the execution id
     */
    public String executionId() {
        return executionId;
    }

    /**
     * The state that does not allow a replay.
     *
     * @return the state of the execution when the replay was refused
     */
    public ExecutionState state() {
        return state;
    }
}
