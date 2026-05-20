package com.nexora.core.plan;

import java.util.Objects;

/**
 * Cancels a pending step. If the step has already started it completes normally;
 * the skip only prevents steps that have not yet begun execution.
 */
public record SkipStepAmendment(String stepId) implements PlanAmendment {
    public SkipStepAmendment {
        Objects.requireNonNull(stepId, "stepId must not be null");
    }
}
