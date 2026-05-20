package com.nexora.core.plan;

import java.util.Objects;

/**
 * Overrides a specific input value for a pending step.
 * Takes precedence over the step's declared InputBinding at resolution time.
 */
public record ModifyInputAmendment(String stepId, String inputKey, Object value) implements PlanAmendment {
    public ModifyInputAmendment {
        Objects.requireNonNull(stepId, "stepId must not be null");
        Objects.requireNonNull(inputKey, "inputKey must not be null");
    }
}
