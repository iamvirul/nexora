package com.nexora.persistence;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public record DeadLetterRecord(
        String id,
        String executionId,
        String goal,
        Map<String, Object> context,
        String failureCode,
        String failureMessage,
        Instant failedAt,
        DeadLetterReviewState reviewState,
        String resolveReason
) {
    public DeadLetterRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(executionId, "executionId");
        Objects.requireNonNull(failedAt, "failedAt");
        Objects.requireNonNull(reviewState, "reviewState");
        context = context == null ? Map.of() : Map.copyOf(context);
    }

    public static DeadLetterRecord pending(String id, String executionId, String goal,
                                           Map<String, Object> context,
                                           String failureCode, String failureMessage,
                                           Instant failedAt) {
        return new DeadLetterRecord(id, executionId, goal, context,
                failureCode, failureMessage, failedAt, DeadLetterReviewState.PENDING, null);
    }
}
