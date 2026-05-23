package com.nexora.core.execution;

import java.util.List;
import java.util.Objects;

public record ExecutionResult(
        String executionId,
        ExecutionStatus status,
        List<StepResult> stepResults
) {
    public ExecutionResult {
        Objects.requireNonNull(executionId, "executionId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        stepResults = stepResults == null ? List.of() : List.copyOf(stepResults);
    }

    public static ExecutionResult completed(String executionId, List<StepResult> stepResults) {
        return new ExecutionResult(executionId, ExecutionStatus.COMPLETED, stepResults);
    }

    public static ExecutionResult failed(String executionId, List<StepResult> stepResults) {
        return new ExecutionResult(executionId, ExecutionStatus.FAILED, stepResults);
    }

    public static ExecutionResult timedOut(String executionId, List<StepResult> stepResults) {
        return new ExecutionResult(executionId, ExecutionStatus.TIMED_OUT, stepResults);
    }
}
