package me.virul.nexora.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import me.virul.nexora.persistence.DeadLetterRecord;
import me.virul.nexora.persistence.DeadLetterReviewState;
import me.virul.nexora.persistence.ExecutionFilter;
import me.virul.nexora.persistence.ExecutionRecord;
import me.virul.nexora.persistence.ExecutionState;
import me.virul.nexora.persistence.ExecutionStore;
import me.virul.nexora.persistence.ScheduleRecord;
import me.virul.nexora.persistence.ScheduleStatus;
import me.virul.nexora.persistence.StepRecord;
import me.virul.nexora.persistence.WebhookDeliveryRecord;

/**
 * Store double at the persistence boundary: delegates to a real store but fails the next N
 * {@code updateExecution} calls for a given state, to simulate a transiently unavailable database.
 */
final class FlakyExecutionStore implements ExecutionStore {

    private final ExecutionStore delegate;
    private final Map<ExecutionState, AtomicInteger> failuresLeft = new ConcurrentHashMap<>();
    private volatile List<ScheduleRecord> frozenActiveSchedules;

    FlakyExecutionStore(ExecutionStore delegate) {
        this.delegate = delegate;
    }

    void failNextUpdates(ExecutionState state, int times) {
        failuresLeft.put(state, new AtomicInteger(times));
    }

    /**
     * From now on, active schedules read as they are at this moment, as two engines starting at once
     * both read them before either fires anything.
     */
    void freezeActiveSchedules() {
        frozenActiveSchedules = delegate.findActiveSchedules();
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
        List<ScheduleRecord> frozen = frozenActiveSchedules;
        return frozen != null ? frozen : delegate.findActiveSchedules();
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
    public boolean claimScheduleFire(String id, Instant expectedNextFireAt, Instant firedAt, Instant nextFireAt) {
        return delegate.claimScheduleFire(id, expectedNextFireAt, firedAt, nextFireAt);
    }

    @Override
    public List<DeadLetterRecord> findDeadLetters(String tenantId, DeadLetterReviewState state, int offset, int limit) {
        return delegate.findDeadLetters(tenantId, state, offset, limit);
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
