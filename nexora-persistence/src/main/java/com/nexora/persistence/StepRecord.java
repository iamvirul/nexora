package com.nexora.persistence;

import java.time.Instant;

public record StepRecord(
        String stepId,
        String capabilityId,
        String idempotencyKey,
        StepState state,
        String failureCode,
        String failureMessage,
        Instant startedAt,
        Instant completedAt,
        long durationMs) {
    public static StepRecord started(String stepId, String capabilityId, String idempotencyKey, Instant startedAt) {
        return new StepRecord(stepId, capabilityId, idempotencyKey, StepState.RUNNING, null, null, startedAt, null, 0L);
    }

    public StepRecord completed(Instant completedAt) {
        long ms = startedAt != null
                ? java.time.Duration.between(startedAt, completedAt).toMillis()
                : 0L;
        return new StepRecord(
                stepId, capabilityId, idempotencyKey, StepState.COMPLETED, null, null, startedAt, completedAt, ms);
    }

    public StepRecord failed(String code, String message, Instant completedAt) {
        long ms = startedAt != null
                ? java.time.Duration.between(startedAt, completedAt).toMillis()
                : 0L;
        return new StepRecord(
                stepId, capabilityId, idempotencyKey, StepState.FAILED, code, message, startedAt, completedAt, ms);
    }

    public StepRecord skipped(Instant completedAt) {
        return new StepRecord(
                stepId, capabilityId, idempotencyKey, StepState.SKIPPED, null, null, startedAt, completedAt, 0L);
    }

    public StepRecord withState(StepState newState) {
        return new StepRecord(
                stepId,
                capabilityId,
                idempotencyKey,
                newState,
                failureCode,
                failureMessage,
                startedAt,
                completedAt,
                durationMs);
    }
}
