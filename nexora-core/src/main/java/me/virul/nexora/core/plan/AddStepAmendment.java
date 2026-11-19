package me.virul.nexora.core.plan;

import java.util.Objects;

/**
 * Injects a new step into the DAG after the named prerequisite steps complete.
 * The inserted step's dependencies must reference steps already present in the plan.
 *
 * @param step the step to add
 */
public record AddStepAmendment(Step step) implements PlanAmendment {

    /**
     * Validates the amendment.
     *
     * @throws NullPointerException if {@code step} is null
     */
    public AddStepAmendment {
        Objects.requireNonNull(step, "step must not be null");
    }
}
