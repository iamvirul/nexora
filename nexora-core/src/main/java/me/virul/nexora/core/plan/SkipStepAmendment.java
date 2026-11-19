package me.virul.nexora.core.plan;

import java.util.Objects;

/**
 * Cancels a pending step. If the step has already started it completes normally;
 * the skip only prevents steps that have not yet begun execution.
 *
 * @param stepId the step to skip
 */
public record SkipStepAmendment(String stepId) implements PlanAmendment {

    /**
     * Validates the amendment.
     *
     * @throws NullPointerException if {@code stepId} is null
     */
    public SkipStepAmendment {
        Objects.requireNonNull(stepId, "stepId must not be null");
    }
}
