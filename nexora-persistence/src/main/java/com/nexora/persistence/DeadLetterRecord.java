package com.nexora.persistence;

import com.nexora.core.tenant.TenantId;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/** @param tenantId the tenant of the execution that failed; {@link TenantId#DEFAULT} when none was given */
public record DeadLetterRecord(
        String id,
        String executionId,
        String goal,
        Map<String, Object> context,
        String failureCode,
        String failureMessage,
        Instant failedAt,
        DeadLetterReviewState reviewState,
        String resolveReason,
        String tenantId) {
    public DeadLetterRecord {
        tenantId = TenantId.orDefault(tenantId);
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(executionId, "executionId");
        Objects.requireNonNull(failedAt, "failedAt");
        Objects.requireNonNull(reviewState, "reviewState");
        context = context == null ? Map.of() : Map.copyOf(context);
    }

    /** A dead letter of the {@link TenantId#DEFAULT} tenant. */
    public DeadLetterRecord(
            String id,
            String executionId,
            String goal,
            Map<String, Object> context,
            String failureCode,
            String failureMessage,
            Instant failedAt,
            DeadLetterReviewState reviewState,
            String resolveReason) {
        this(id, executionId, goal, context, failureCode, failureMessage, failedAt, reviewState, resolveReason, null);
    }

    public static DeadLetterRecord pending(
            String id,
            String executionId,
            String goal,
            Map<String, Object> context,
            String failureCode,
            String failureMessage,
            Instant failedAt) {
        return pending(id, executionId, goal, context, failureCode, failureMessage, failedAt, null);
    }

    public static DeadLetterRecord pending(
            String id,
            String executionId,
            String goal,
            Map<String, Object> context,
            String failureCode,
            String failureMessage,
            Instant failedAt,
            String tenantId) {
        return new DeadLetterRecord(
                id,
                executionId,
                goal,
                context,
                failureCode,
                failureMessage,
                failedAt,
                DeadLetterReviewState.PENDING,
                null,
                tenantId);
    }
}
