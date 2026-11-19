package me.virul.nexora.core.plan;

import java.util.Objects;

/**
 * Overrides a specific input value for a pending step.
 * Takes precedence over the step's declared InputBinding at resolution time.
 *
 * @param stepId   the pending step
 * @param inputKey the input to override
 * @param value    the value to use
 */
public record ModifyInputAmendment(String stepId, String inputKey, Object value) implements PlanAmendment {

    /**
     * Validates the amendment.
     *
     * @throws NullPointerException if {@code stepId} or {@code inputKey} is null
     */
    public ModifyInputAmendment {
        Objects.requireNonNull(stepId, "stepId must not be null");
        Objects.requireNonNull(inputKey, "inputKey must not be null");
    }
}
