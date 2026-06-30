package com.nexora.runtime.scheduler;

import java.time.Instant;

/** Handle returned by {@code NexoraEngine.schedule()}. Thread-safe. */
public final class ScheduledExecution {

    private final String id;
    private final String cronExpression;
    private final Instant nextFireTime;
    private final CronScheduler owner;

    ScheduledExecution(String id, String cronExpression, Instant nextFireTime, CronScheduler owner) {
        this.id = id;
        this.cronExpression = cronExpression;
        this.nextFireTime = nextFireTime;
        this.owner = owner;
    }

    public String id() { return id; }

    public String cronExpression() { return cronExpression; }

    public Instant nextFireTime() { return nextFireTime; }

    /** Cancels this schedule. Future firings will not occur. Idempotent. */
    public void cancel() {
        owner.cancel(id);
    }
}
