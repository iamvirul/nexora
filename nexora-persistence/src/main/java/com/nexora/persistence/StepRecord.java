package com.nexora.persistence;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One step of a persisted execution.
 *
 * @param output          what the step returned, as recorded (see {@link StepOutput}); empty when
 *                        it produced none, has not completed, or failed
 * @param outputTruncated whether {@code output} is a preview of an output larger than the cap
 */
public record StepRecord(
        String stepId,
        String capabilityId,
        String idempotencyKey,
        StepState state,
        String failureCode,
        String failureMessage,
        Instant startedAt,
        Instant completedAt,
        long durationMs,
        Map<String, Object> output,
        boolean outputTruncated) {

    public StepRecord {
        // Not Map.copyOf: JSON objects may hold null values.
        output = output == null || output.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(output));
    }

    /** A step with no recorded output, as before outputs were stored. */
    public StepRecord(
            String stepId,
            String capabilityId,
            String idempotencyKey,
            StepState state,
            String failureCode,
            String failureMessage,
            Instant startedAt,
            Instant completedAt,
            long durationMs) {
        this(
                stepId,
                capabilityId,
                idempotencyKey,
                state,
                failureCode,
                failureMessage,
                startedAt,
                completedAt,
                durationMs,
                Map.of(),
                false);
    }

    public static StepRecord started(String stepId, String capabilityId, String idempotencyKey, Instant startedAt) {
        return new StepRecord(stepId, capabilityId, idempotencyKey, StepState.RUNNING, null, null, startedAt, null, 0L);
    }

    public StepRecord completed(Instant completedAt) {
        return completed(completedAt, StepOutput.NONE);
    }

    public StepRecord completed(Instant completedAt, StepOutput output) {
        return new StepRecord(
                stepId,
                capabilityId,
                idempotencyKey,
                StepState.COMPLETED,
                null,
                null,
                startedAt,
                completedAt,
                elapsedUntil(completedAt),
                output.value(),
                output.truncated());
    }

    public StepRecord failed(String code, String message, Instant completedAt) {
        return new StepRecord(
                stepId,
                capabilityId,
                idempotencyKey,
                StepState.FAILED,
                code,
                message,
                startedAt,
                completedAt,
                elapsedUntil(completedAt));
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
                durationMs,
                output,
                outputTruncated);
    }

    private long elapsedUntil(Instant completedAt) {
        return startedAt != null ? Duration.between(startedAt, completedAt).toMillis() : 0L;
    }
}
