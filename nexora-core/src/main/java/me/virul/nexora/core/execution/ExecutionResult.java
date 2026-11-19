package me.virul.nexora.core.execution;

import java.util.List;
import java.util.Objects;

/**
 * The outcome of a finished execution.
 *
 * @param executionId the id of the execution
 * @param status      how it ended
 * @param stepResults the result of each step that ran or was skipped; never null and immutable
 */
public record ExecutionResult(String executionId, ExecutionStatus status, List<StepResult> stepResults) {

    /**
     * Validates the result and copies its step results, treating {@code null} as empty.
     *
     * @throws NullPointerException if {@code executionId} or {@code status} is null
     */
    public ExecutionResult {
        Objects.requireNonNull(executionId, "executionId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        stepResults = stepResults == null ? List.of() : List.copyOf(stepResults);
    }

    /**
     * A result with status {@link ExecutionStatus#COMPLETED}.
     *
     * @param executionId the id of the execution
     * @param stepResults the step results
     * @return the result
     */
    public static ExecutionResult completed(String executionId, List<StepResult> stepResults) {
        return new ExecutionResult(executionId, ExecutionStatus.COMPLETED, stepResults);
    }

    /**
     * A result with status {@link ExecutionStatus#FAILED}.
     *
     * @param executionId the id of the execution
     * @param stepResults the step results
     * @return the result
     */
    public static ExecutionResult failed(String executionId, List<StepResult> stepResults) {
        return new ExecutionResult(executionId, ExecutionStatus.FAILED, stepResults);
    }

    /**
     * A result with status {@link ExecutionStatus#TIMED_OUT}.
     *
     * @param executionId the id of the execution
     * @param stepResults the results of the steps that finished before the deadline
     * @return the result
     */
    public static ExecutionResult timedOut(String executionId, List<StepResult> stepResults) {
        return new ExecutionResult(executionId, ExecutionStatus.TIMED_OUT, stepResults);
    }

    /**
     * A result with status {@link ExecutionStatus#CANCELLED}.
     *
     * @param executionId the id of the execution
     * @param stepResults the results of the steps that finished before the cancellation
     * @return the result
     */
    public static ExecutionResult cancelled(String executionId, List<StepResult> stepResults) {
        return new ExecutionResult(executionId, ExecutionStatus.CANCELLED, stepResults);
    }
}
