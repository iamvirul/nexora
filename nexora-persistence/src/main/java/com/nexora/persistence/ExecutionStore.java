package com.nexora.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * SPI for durable execution state. Implement and pass to NexoraEngine.Builder.withExecutionStore()
 * to replace the default H2 store with any backend (PostgreSQL, MySQL, DynamoDB, etc.).
 *
 * All methods must be thread-safe. Implementations should treat upsertStep as idempotent
 * when called with the same (executionId, stepId, idempotencyKey).
 */
public interface ExecutionStore extends AutoCloseable {

    void createExecution(ExecutionRecord record);

    void updateExecution(String executionId, ExecutionState state, Instant completedAt);

    /** Upserts step-level state into the store. */
    void upsertStep(String executionId, StepRecord step);

    /** Records an outgoing webhook delivery attempt for auditability. */
    void recordWebhookDelivery(WebhookDeliveryRecord record);

    /** Returns all webhook delivery attempts for a given execution. */
    List<WebhookDeliveryRecord> getWebhookDeliveries(String executionId);

    Optional<ExecutionRecord> findById(String executionId);

    List<ExecutionRecord> findRecent(int limit);

    // --- Dead Letter Queue ---

    /** Persists a new dead letter record in PENDING state. */
    default void createDeadLetter(DeadLetterRecord record) {
        throw new UnsupportedOperationException("DLQ not supported by this store implementation");
    }

    /** Finds a dead letter by its own id. */
    default Optional<DeadLetterRecord> findDeadLetterById(String id) {
        return Optional.empty();
    }

    /**
     * Returns a page of dead letters filtered by review state.
     *
     * @param state  filter; {@code null} returns all states
     * @param offset zero-based row offset
     * @param limit  max rows to return
     */
    default List<DeadLetterRecord> findDeadLetters(DeadLetterReviewState state, int offset, int limit) {
        return List.of();
    }

    /**
     * Transitions a dead letter to the given state.
     *
     * @param resolveReason optional human-readable reason; only meaningful for RESOLVED transitions
     */
    default void updateDeadLetterState(String id, DeadLetterReviewState state, String resolveReason) {
        throw new UnsupportedOperationException("DLQ not supported by this store implementation");
    }

    @Override
    void close();
}
