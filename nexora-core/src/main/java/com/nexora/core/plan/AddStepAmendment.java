package com.nexora.core.plan;

import java.util.Objects;
import java.util.Set;

/**
 * Injects a new step into the DAG after the named prerequisite steps complete.
 * The inserted step's dependencies must reference steps already present in the plan.
 */
public record AddStepAmendment(Step step) implements PlanAmendment {
    public AddStepAmendment {
        Objects.requireNonNull(step, "step must not be null");
    }
}
