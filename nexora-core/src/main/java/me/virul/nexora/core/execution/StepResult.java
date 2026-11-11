package me.virul.nexora.core.execution;

import java.util.Objects;
import me.virul.nexora.core.capability.CapabilityResult;

public record StepResult(String stepId, CapabilityResult capabilityResult) {
    public StepResult {
        Objects.requireNonNull(stepId, "stepId must not be null");
        Objects.requireNonNull(capabilityResult, "capabilityResult must not be null");
    }

    public ExecutionStatus getStatus() {
        return capabilityResult.succeeded() ? ExecutionStatus.COMPLETED : ExecutionStatus.FAILED;
    }

    public boolean succeeded() {
        return capabilityResult.succeeded();
    }
}
