package com.nexora.runtime.scheduler;

import com.nexora.core.intent.Intent;
import com.nexora.event.ExecutionEventBus;
import com.nexora.event.ScheduledExecutionFiredEvent;
import com.nexora.event.ScheduledExecutionMissedEvent;
import com.nexora.persistence.ExecutionStore;
import com.nexora.persistence.MissedFirePolicy;
import com.nexora.persistence.ScheduleRecord;
import com.nexora.persistence.ScheduleStatus;
import com.nexora.runtime.engine.ExecutionEngine;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * In-process cron scheduler backed by the {@link ExecutionStore}.
 *
 * Schedules survive engine restarts: on startup, all active schedules are
 * reloaded from the store and rescheduled, applying the configured
 * {@link MissedFirePolicy} for any windows missed while the engine was down.
 *
 * Each schedule belongs to the tenant of the intent it was created with, and every firing runs
 * for that tenant. A scheduler limited to one tenant reloads, lists and fires only that tenant's
 * schedules.
 *
 * Engines sharing a store each enqueue the schedules they see, so several may wake for the same
 * slot. Each firing is claimed in the store first ({@link ExecutionStore#claimScheduleFire}); only
 * the claimant runs it, so every slot fires once however many engines share the store.
 */
public final class CronScheduler implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(CronScheduler.class);

    private final ExecutionEngine engine;
    private final ExecutionStore store;
    private final ExecutionEventBus eventBus;
    private final ScheduledExecutorService scheduler;
    private final ConcurrentHashMap<String, ScheduledFuture<?>> activeFutures = new ConcurrentHashMap<>();
    private final String tenantScope; // null = every tenant's schedules
    // A wake-up this close to the stored slot still claims it: the timer and the wall clock drift by
    // milliseconds, and treating an early wake-up as already fired would only spin until the slot.
    private static final java.time.Duration DUE_TOLERANCE = java.time.Duration.ofSeconds(1);

    public CronScheduler(ExecutionEngine engine, ExecutionStore store, ExecutionEventBus eventBus) {
        this(engine, store, eventBus, null);
    }

    /** @param tenantScope the only tenant whose schedules this scheduler runs; {@code null} runs every tenant's */
    public CronScheduler(ExecutionEngine engine, ExecutionStore store, ExecutionEventBus eventBus, String tenantScope) {
        this.engine = engine;
        this.store = store;
        this.eventBus = eventBus;
        this.tenantScope = tenantScope;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "nexora-cron-scheduler");
            t.setDaemon(true);
            return t;
        });
        reloadFromStore();
    }

    /**
     * Schedules a recurring execution of {@code intent} according to {@code cronExpression}.
     *
     * @param cronExpression 5-field UNIX cron expression (minute hour dom month dow)
     * @param intent         the intent to execute on each firing
     * @param policy         behaviour when windows are missed after a restart
     * @return a handle that can be used to query the next fire time or cancel the schedule
     */
    public ScheduledExecution schedule(String cronExpression, Intent intent, MissedFirePolicy policy) {
        CronExpression cron = CronExpression.parse(cronExpression);
        ZonedDateTime nextFire = cron.next(ZonedDateTime.now(ZoneOffset.UTC));
        Instant now = Instant.now();

        ScheduleRecord record = new ScheduleRecord(
                UUID.randomUUID().toString(),
                cronExpression,
                intent.getGoal(),
                intent.getContext(),
                policy,
                now,
                null,
                nextFire.toInstant(),
                ScheduleStatus.NEVER_RUN,
                true,
                intent.getTenantId());
        store.createSchedule(record);
        enqueue(record.id(), nextFire);

        log.info(
                "Schedule registered id={} tenantId={} cron='{}' nextFire={}",
                record.id(),
                record.tenantId(),
                cronExpression,
                nextFire);
        return new ScheduledExecution(record.id(), cronExpression, nextFire.toInstant(), this);
    }

    /** Cancels a schedule by id. Idempotent — safe to call multiple times. */
    public void cancel(String scheduleId) {
        ScheduledFuture<?> future = activeFutures.remove(scheduleId);
        if (future != null) future.cancel(false);
        store.deactivateSchedule(scheduleId);
        log.info("Schedule cancelled id={}", scheduleId);
    }

    /** Returns all schedules known to the store (active and inactive). */
    public List<ScheduleRecord> listAll() {
        return inScope(store.findAllSchedules());
    }

    /** Returns only active schedules. */
    public List<ScheduleRecord> listActive() {
        return inScope(store.findActiveSchedules());
    }

    /** The schedule with this id, if it exists and is within this scheduler's tenant scope. */
    public Optional<ScheduleRecord> find(String scheduleId) {
        return store.findScheduleById(scheduleId).filter(this::inScope);
    }

    private List<ScheduleRecord> inScope(List<ScheduleRecord> records) {
        return tenantScope == null
                ? records
                : records.stream().filter(this::inScope).toList();
    }

    private boolean inScope(ScheduleRecord record) {
        return tenantScope == null || tenantScope.equals(record.tenantId());
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }

    // --- internals ---

    private void reloadFromStore() {
        List<ScheduleRecord> active = listActive();
        if (active.isEmpty()) return;
        log.info("Reloading {} active schedule(s) from store", active.size());
        for (ScheduleRecord record : active) {
            try {
                CronExpression cron = CronExpression.parse(record.cronExpression());
                handleMissedFires(record, cron);
            } catch (Exception e) {
                log.error("Failed to reload schedule id={}", record.id(), e);
            }
        }
    }

    private void handleMissedFires(ScheduleRecord record, CronExpression cron) {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        Instant from = record.lastFiredAt() != null ? record.lastFiredAt() : record.createdAt();
        ZonedDateTime fromDt = from.atZone(ZoneOffset.UTC);

        List<ZonedDateTime> missed = collectMissedFirings(record.id(), cron, fromDt, now);
        ZonedDateTime nextFire = cron.next(now);

        if (missed.isEmpty() || record.missedFirePolicy() == MissedFirePolicy.SKIP) {
            if (!missed.isEmpty()) {
                log.warn("Schedule id={} missed {} window(s); SKIP policy, not firing", record.id(), missed.size());
                store.updateScheduleStatus(record.id(), ScheduleStatus.MISSED);
                eventBus.publish(new ScheduledExecutionMissedEvent(record.id(), missed.size(), from, now.toInstant()));
            }
            store.updateScheduleNextFire(record.id(), nextFire.toInstant());
            enqueue(record.id(), nextFire);
            return;
        }

        // Every engine sharing the store reloads the same overdue schedule; only the claimant catches up.
        if (!store.claimScheduleFire(record.id(), record.nextFireAt(), now.toInstant(), nextFire.toInstant())) {
            log.info("Schedule id={} catch-up already claimed by another engine", record.id());
            enqueueAtStoredNextFire(record.id());
            return;
        }
        if (record.missedFirePolicy() == MissedFirePolicy.FIRE_ONCE) {
            log.info("Schedule id={} missed {} window(s); FIRE_ONCE, firing once now", record.id(), missed.size());
            executeAndPublish(record);
            int extra = missed.size() - 1;
            if (extra > 0) {
                eventBus.publish(new ScheduledExecutionMissedEvent(record.id(), extra, from, now.toInstant()));
            }
        } else {
            log.info(
                    "Schedule id={} missed {} window(s); FIRE_ALL, firing {} time(s)",
                    record.id(),
                    missed.size(),
                    missed.size());
            for (int i = 0; i < missed.size(); i++) {
                executeAndPublish(record);
            }
        }
        enqueue(record.id(), nextFire);
    }

    /**
     * Claims the due firing before running it. Every engine sharing the store enqueues the same
     * schedules, so each wakes for the same slot; the store's compare-and-set lets exactly one run
     * it, and the others wait for the slot that engine moved the schedule on to.
     */
    private void onFire(String scheduleId) {
        ScheduleRecord record = store.findScheduleById(scheduleId).orElse(null);
        if (record == null || !record.active() || !inScope(record)) {
            activeFutures.remove(scheduleId);
            return;
        }

        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        if (record.nextFireAt().isAfter(now.toInstant().plus(DUE_TOLERANCE))) {
            // Another engine already ran this slot and moved the schedule on.
            enqueue(scheduleId, record.nextFireAt().atZone(ZoneOffset.UTC));
            return;
        }
        ZonedDateTime nextFire =
                nextSlotAfterClaim(CronExpression.parse(record.cronExpression()), record.nextFireAt(), now);
        if (!store.claimScheduleFire(scheduleId, record.nextFireAt(), now.toInstant(), nextFire.toInstant())) {
            log.debug("Schedule id={} firing claimed by another engine", scheduleId);
            enqueueAtStoredNextFire(scheduleId);
            return;
        }

        log.debug("Firing schedule id={} goal='{}'", scheduleId, record.goal());
        executeAndPublish(record);
        enqueue(scheduleId, nextFire);
    }

    /**
     * The slot a claim moves the schedule to: the first after both the claimed slot and now. A wake-up
     * within the tolerance before the slot would otherwise get that same slot back from {@code
     * next(now)}, store it unchanged, and run it a second time when it comes round.
     */
    static ZonedDateTime nextSlotAfterClaim(CronExpression cron, Instant claimedSlot, ZonedDateTime now) {
        ZonedDateTime slot = claimedSlot.atZone(ZoneOffset.UTC);
        return cron.next(slot.isAfter(now) ? slot : now);
    }

    /** After losing a claim: wait for the slot the winner moved the schedule to, unless it is gone. */
    private void enqueueAtStoredNextFire(String scheduleId) {
        store.findScheduleById(scheduleId)
                .filter(ScheduleRecord::active)
                .ifPresentOrElse(
                        current -> enqueue(scheduleId, current.nextFireAt().atZone(ZoneOffset.UTC)),
                        () -> activeFutures.remove(scheduleId));
    }

    private void executeAndPublish(ScheduleRecord record) {
        String scheduleId = record.id();
        Intent intent = new Intent(record.goal(), record.context(), null, null, null, record.tenantId());
        engine.execute(intent).whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("Scheduled execution failed scheduleId={}", scheduleId, ex);
                store.updateScheduleStatus(scheduleId, ScheduleStatus.FAILED);
            } else {
                store.updateScheduleStatus(scheduleId, ScheduleStatus.FIRED);
                eventBus.publish(new ScheduledExecutionFiredEvent(scheduleId, result.executionId(), Instant.now()));
            }
        });
    }

    private void enqueue(String scheduleId, ZonedDateTime nextFire) {
        long delayMs =
                java.time.Duration.between(Instant.now(), nextFire.toInstant()).toMillis();
        if (delayMs < 0) delayMs = 0;
        ScheduledFuture<?> future = scheduler.schedule(() -> onFire(scheduleId), delayMs, TimeUnit.MILLISECONDS);
        activeFutures.put(scheduleId, future);
        log.debug("Enqueued schedule id={} next={} delayMs={}", scheduleId, nextFire, delayMs);
    }

    private static List<ZonedDateTime> collectMissedFirings(
            String scheduleId, CronExpression cron, ZonedDateTime from, ZonedDateTime to) {
        List<ZonedDateTime> missed = new ArrayList<>();
        ZonedDateTime cursor = from;
        while (true) {
            ZonedDateTime next = cron.next(cursor);
            if (!next.isBefore(to)) break;
            missed.add(next);
            cursor = next;
            if (missed.size() >= 1000) {
                log.warn("Schedule id={} has 1000+ missed windows; truncating catch-up to 1000", scheduleId);
                break;
            }
        }
        return missed;
    }
}
