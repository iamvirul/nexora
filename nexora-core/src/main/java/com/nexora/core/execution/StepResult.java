package com.nexora.core.execution;

public class StepResult {

    private final String stepName;
    private final ExecutionStatus status;
    private final String message;

    public StepResult(String stepName, ExecutionStatus status, String message) {
        this.stepName = stepName;
        this.status = status;
        this.message = message;
    }

    public String getMessage() {
        return message;
    }

    public ExecutionStatus getStatus() {
        return status;
    }

    public String getStepName() {
        return stepName;
    }
}
