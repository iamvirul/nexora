package com.nexora.core.execution;

import java.util.List;

public class ExecutionResult {

    private final ExecutionStatus status;
    private final List<StepResult> stepResults;

    public ExecutionResult(ExecutionStatus status, List<StepResult> stepResults) {
        this.status = status;
        this.stepResults = stepResults;
    }

    public ExecutionStatus getStatus() {
        return status;
    }

    public List<StepResult> getStepResults() {
        return stepResults;
    }
}
