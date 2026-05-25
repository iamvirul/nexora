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

    @Override
    void close();
}
