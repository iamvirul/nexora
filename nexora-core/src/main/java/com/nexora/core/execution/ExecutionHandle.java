package com.nexora.core.execution;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * A submitted execution: its id is known immediately, so callers can track or cancel it
 * while {@code result} is still pending.
 */
public record ExecutionHandle(String executionId, CompletableFuture<ExecutionResult> result) {
    public ExecutionHandle {
        Objects.requireNonNull(executionId, "executionId must not be null");
        Objects.requireNonNull(result, "result must not be null");
    }
}
