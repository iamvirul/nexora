package me.virul.nexora.core.execution;

import java.util.Objects;
import me.virul.nexora.core.capability.CapabilityResult;

/**
 * The outcome of one plan step.
 *
 * @param stepId           the step
 * @param capabilityResult the result of its last capability call, or of the skip
 */
public record StepResult(String stepId, CapabilityResult capabilityResult) {

    /**
     * Validates the step result.
     *
     * @throws NullPointerException if either component is null
     */
    public StepResult {
        Objects.requireNonNull(stepId, "stepId must not be null");
        Objects.requireNonNull(capabilityResult, "capabilityResult must not be null");
    }

    /**
     * The step outcome as an execution status.
     *
     * @return {@link ExecutionStatus#COMPLETED} when the step succeeded, otherwise
     *         {@link ExecutionStatus#FAILED}, skips included
     */
    public ExecutionStatus getStatus() {
        return capabilityResult.succeeded() ? ExecutionStatus.COMPLETED : ExecutionStatus.FAILED;
    }

    /**
     * Whether the step succeeded.
     *
     * @return {@code true} when its capability result succeeded
     */
    public boolean succeeded() {
        return capabilityResult.succeeded();
    }
}
