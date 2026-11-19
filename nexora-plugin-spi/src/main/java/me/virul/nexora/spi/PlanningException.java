package me.virul.nexora.spi;

/**
 * Thrown by a {@link Planner} when it cannot plan an intent. It fails the execution; throw
 * {@link PlanningDeclinedException} instead to let the next planner try.
 */
public class PlanningException extends RuntimeException {

    /**
     * Creates the exception.
     *
     * @param message why planning failed
     */
    public PlanningException(String message) {
        super(message);
    }

    /**
     * Creates the exception with the error that caused it.
     *
     * @param message why planning failed
     * @param cause   the underlying error
     */
    public PlanningException(String message, Throwable cause) {
        super(message, cause);
    }
}
