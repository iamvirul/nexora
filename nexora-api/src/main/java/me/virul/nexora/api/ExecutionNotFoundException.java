package me.virul.nexora.api;

/** No execution with the given id is known to this engine or its persistence store. */
public final class ExecutionNotFoundException extends RuntimeException {

    /** The id that was looked up. */
    private final String executionId;

    /**
     * Creates the exception.
     *
     * @param executionId the id that was looked up
     */
    public ExecutionNotFoundException(String executionId) {
        super("No execution found with id: " + executionId);
        this.executionId = executionId;
    }

    /**
     * The id that was looked up.
     *
     * @return the execution id
     */
    public String executionId() {
        return executionId;
    }
}
