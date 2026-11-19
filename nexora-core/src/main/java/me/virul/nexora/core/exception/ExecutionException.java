package me.virul.nexora.core.exception;

/** A failure to plan or run an execution, for code that reports one as an unchecked exception. */
public class ExecutionException extends RuntimeException {

    /**
     * Creates the exception.
     *
     * @param message what went wrong
     */
    public ExecutionException(String message) {
        super(message);
    }

    /**
     * Creates the exception with the error that caused it.
     *
     * @param message what went wrong
     * @param cause   the underlying error
     */
    public ExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
