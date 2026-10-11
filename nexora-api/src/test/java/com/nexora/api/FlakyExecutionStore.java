package com.nexora.api;

import com.nexora.persistence.DeadLetterRecord;
import com.nexora.persistence.DeadLetterReviewState;
import com.nexora.persistence.ExecutionFilter;
import com.nexora.persistence.ExecutionRecord;
import com.nexora.persistence.ExecutionState;
import com.nexora.persistence.ExecutionStore;
import com.nexora.persistence.ScheduleRecord;
import com.nexora.persistence.ScheduleStatus;
import com.nexora.persistence.StepRecord;
import com.nexora.persistence.WebhookDeliveryRecord;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Store double at the persistence boundary: delegates to a real store but fails the next N
 * {@code updateExecution} calls for a given state, to simulate a transiently unavailable database.
 */
final class FlakyExecutionStore implements ExecutionStore {

    private final ExecutionStore delegate;
    private final Map<ExecutionState, AtomicInteger> failuresLeft = new ConcurrentHashMap<>();

    FlakyExecutionStore(ExecutionStore delegate) {
        this.delegate = delegate;
    }

    void failNextUpdates(ExecutionState state, int times) {
        failuresLeft.put(state, new AtomicInteger(times));
    }

    @Override
    public void updateExecution(String executionId, ExecutionState state, Instant completedAt) {
        AtomicInteger left = failuresLeft.get(state);
        if (left != null && left.getAndDecrement() > 0) {
            throw new IllegalStateException("simulated store failure writing " + state);
        }
        delegate.updateExecution(executionId, state, completedAt);
    }

    @Override
    public void createExecution(ExecutionRecord record) {
        delegate.createExecution(record);
    }

    @Override
    public void upsertStep(String executionId, StepRecord step) {
        delegate.upsertStep(executionId, step);
    }

    @Override
    public void recordWebhookDelivery(WebhookDeliveryRecord record) {
        delegate.recordWebhookDelivery(record);
    }

    @Override
    public List<WebhookDeliveryRecord> getWebhookDeliveries(String executionId) {
        return delegate.getWebhookDeliveries(executionId);
    }

    @Override
    public Optional<ExecutionRecord> findById(String executionId) {
        return delegate.findById(executionId);
    }

    @Override
    public List<ExecutionRecord> findRecent(int limit) {
        return delegate.findRecent(limit);
    }

    @Override
    public List<ExecutionRecord> findByFilter(ExecutionFilter filter) {
        return delegate.findByFilter(filter);
    }

    @Override
    public long count(ExecutionFilter filter) {
        return delegate.count(filter);
    }

    @Override
    public void createDeadLetter(DeadLetterRecord record) {
        delegate.createDeadLetter(record);
    }

    @Override
    public Optional<DeadLetterRecord> findDeadLetterById(String id) {
        return delegate.findDeadLetterById(id);
    }

    @Override
    public List<DeadLetterRecord> findDeadLetters(DeadLetterReviewState state, int offset, int limit) {
        return delegate.findDeadLetters(state, offset, limit);
    }

    @Override
    public void updateDeadLetterState(String id, DeadLetterReviewState state, String reason) {
        delegate.updateDeadLetterState(id, state, reason);
    }

    @Override
    public boolean isHealthy() {
        return delegate.isHealthy();
    }

    @Override
    public void createSchedule(ScheduleRecord record) {
        delegate.createSchedule(record);
    }

    @Override
    public Optional<ScheduleRecord> findScheduleById(String id) {
        return delegate.findScheduleById(id);
    }

    @Override
    public List<ScheduleRecord> findActiveSchedules() {
        return delegate.findActiveSchedules();
    }

    @Override
    public List<ScheduleRecord> findAllSchedules() {
        return delegate.findAllSchedules();
    }

    @Override
    public void updateScheduleNextFire(String id, Instant nextFireAt) {
        delegate.updateScheduleNextFire(id, nextFireAt);
    }

    @Override
    public void updateScheduleLastFired(String id, Instant lastFiredAt, Instant nextFireAt) {
        delegate.updateScheduleLastFired(id, lastFiredAt, nextFireAt);
    }

    @Override
    public void deactivateSchedule(String id) {
        delegate.deactivateSchedule(id);
    }

    @Override
    public void updateScheduleStatus(String id, ScheduleStatus status) {
        delegate.updateScheduleStatus(id, status);
    }

    @Override
    public void close() {
        delegate.close();
    }
}
