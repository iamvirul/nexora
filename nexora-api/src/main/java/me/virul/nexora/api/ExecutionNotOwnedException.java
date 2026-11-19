package me.virul.nexora.api;

/**
 * The execution is recorded as RUNNING in the store but is not running on this engine, so this
 * engine cannot stop it. Either another engine instance owns it, or a previous process died
 * mid-execution and left the record behind.
 */
public final class ExecutionNotOwnedException extends RuntimeException {

    /** The execution that could not be stopped. */
    private final String executionId;

    /**
     * Creates the exception.
     *
     * @param executionId the execution that could not be stopped
     */
    public ExecutionNotOwnedException(String executionId) {
        super("Execution " + executionId + " is RUNNING in the store but not on this engine instance");
        this.executionId = executionId;
    }

    /**
     * The execution that could not be stopped.
     *
     * @return the execution id
     */
    public String executionId() {
        return executionId;
    }
}
