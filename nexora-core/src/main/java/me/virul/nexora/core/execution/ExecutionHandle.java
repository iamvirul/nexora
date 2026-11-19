package me.virul.nexora.core.execution;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * A submitted execution: its id is known immediately, so callers can track or cancel it
 * while {@code result} is still pending.
 *
 * @param executionId the id of the execution
 * @param result      completes with the result once the execution finishes
 */
public record ExecutionHandle(String executionId, CompletableFuture<ExecutionResult> result) {

    /**
     * Validates the handle.
     *
     * @throws NullPointerException if either component is null
     */
    public ExecutionHandle {
        Objects.requireNonNull(executionId, "executionId must not be null");
        Objects.requireNonNull(result, "result must not be null");
    }
}
