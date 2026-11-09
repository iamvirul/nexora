package com.nexora.spi;

/**
 * Thrown by {@link Planner#plan} when a planner that accepted the intent in {@code canPlan} finds it
 * cannot produce a usable plan after all, for example because a remote model returned an invalid
 * plan or was unreachable. The engine's composite planner then tries the next planner in priority
 * order, ending with the built-in rule planner, instead of failing the execution.
 *
 * <p>Any other {@link PlanningException} still fails planning.
 */
public class PlanningDeclinedException extends PlanningException {

    /** @param message why the planner could not plan; logged by the composite planner */
    public PlanningDeclinedException(String message) {
        super(message);
    }

    /** @param cause the failure that made the planner decline, such as a failed model call */
    public PlanningDeclinedException(String message, Throwable cause) {
        super(message, cause);
    }
}
