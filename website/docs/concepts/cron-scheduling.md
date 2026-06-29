---
id: cron-scheduling
title: Cron Scheduling
sidebar_position: 7
---

# Cron Scheduling (Unreleased)

:::info Unreleased Version
This feature is implemented in the unreleased development version of Nexora.
:::

Nexora's built-in cron scheduler lets you register recurring executions backed by the existing persistence layer. Schedules survive engine restarts and missed-fire behaviour is configurable per schedule.

---

## How It Works

1. **Register** — call `engine.schedule(cronExpression, intent)` or `nexora schedule add`. The schedule is persisted to `nexora_schedules` and the next fire time is calculated immediately.
2. **Fire** — an in-process `ScheduledExecutorService` wakes at the calculated next-fire instant and calls `engine.execute(intent)`.
3. **Missed-fire check** — on each startup, the scheduler loads all active schedules from the store. For any schedule whose `next_fire_at` is in the past it applies the configured `MissedFirePolicy` before rescheduling.
4. **Events** — `ScheduledExecutionFiredEvent` is published after each successful dispatch; `ScheduledExecutionMissedEvent` is published when windows are skipped.
5. **Reschedule** — after firing, `last_fired_at` and `next_fire_at` are updated in the store, and the next wakeup is enqueued.

---

## ScheduleRecord Fields

| Field | Type | Description |
|-------|------|-------------|
| `id` | `String` (UUID) | Unique schedule identifier |
| `cronExpression` | `String` | 5-field UNIX cron expression |
| `goal` | `String` | Intent goal string fired on each tick |
| `context` | `Map<String, Object>` | Static context passed to each execution |
| `missedFirePolicy` | `MissedFirePolicy` | `SKIP`, `FIRE_ONCE`, or `FIRE_ALL` |
| `createdAt` | `Instant` | When the schedule was registered |
| `lastFiredAt` | `Instant` (nullable) | When the schedule last fired |
| `nextFireAt` | `Instant` | Next scheduled fire time |
| `active` | `boolean` | `false` once cancelled |

---

## Missed-fire Policies

When the engine is restarted after downtime, some cron windows may have been missed.

| Policy | Behaviour |
|--------|-----------|
| `SKIP` | Drop all missed windows. Wait for the next scheduled time. Publishes a `ScheduledExecutionMissedEvent` with the count. |
| `FIRE_ONCE` | Fire exactly once immediately to catch up, regardless of how many windows were missed. This is the default. |
| `FIRE_ALL` | Fire once for each missed window. Use with care on high-frequency crons. |

---

## Cron Expression Format

Standard 5-field UNIX cron (no seconds, no year):

```
┌──── minute      (0–59)
│ ┌──── hour        (0–23)
│ │ ┌──── day-of-month (1–31)
│ │ │ ┌──── month        (1–12)
│ │ │ │ ┌──── day-of-week  (0–6, 0=Sunday; 7 also accepted as Sunday)
│ │ │ │ │
* * * * *
```

Supported syntax per field: `*`, `N`, `N-M` (range), `*/step`, `N/step`, `N,M,...` (list).

```
0 0 * * *       every day at midnight
0 9 * * 1-5     09:00 on weekdays
*/5 * * * *     every 5 minutes
0 0 1 * *       first of every month at midnight
30 8,17 * * *   08:30 and 17:30 every day
```

:::note
If both day-of-month and day-of-week are restricted (neither is `*`), a day matches if **either** constraint is satisfied — standard UNIX cron OR semantics.
:::

---

## Java API

```java
NexoraEngine engine = NexoraEngine.builder()
        .withExecutionStore(JdbcExecutionStore.h2("./nexora-data"))
        .withPlugin(myPlugin)
        .build();

// Register a nightly report
ScheduledExecution handle = engine.schedule(
        "0 0 * * *",
        new Intent("run nightly report", Map.of("reportId", "daily-summary"))
);

System.out.println("Next fire: " + handle.nextFireTime());

// Cancel at any time
handle.cancel();

// Or cancel by ID
engine.cancelSchedule(handle.id());

// List all schedules
List<ScheduleRecord> active = engine.listActiveSchedules();
List<ScheduleRecord> all    = engine.listSchedules();
```

### With explicit missed-fire policy

```java
engine.schedule(
        "*/5 * * * *",
        new Intent("poll external api", Map.of()),
        MissedFirePolicy.SKIP   // don't back-fill missed polls on restart
);
```

---

## Subscribing to Events

```java
engine.subscribe(ScheduledExecutionFiredEvent.class, e ->
        log.info("Schedule fired scheduleId={} executionId={} at={}",
                e.scheduleId(), e.executionId(), e.firedAt()));

engine.subscribe(ScheduledExecutionMissedEvent.class, e ->
        alerting.warn("Schedule missed {} windows scheduleId={} from={} to={}",
                e.missedCount(), e.scheduleId(), e.windowStart(), e.windowEnd()));
```

---

## Persistence

Schedules are stored in the `nexora_schedules` table, created automatically on first use alongside the existing `nexora_executions` schema. A persistence store is required:

```java
NexoraEngine.builder()
        .withExecutionStore(JdbcExecutionStore.h2("./data/nexora"))
        // ...
```

Without a store, calling `engine.schedule(...)` throws `IllegalStateException`.

---

## Observability Dashboard

When running `nexora observe`, the **Cron Schedules** panel at the bottom of the UI lets you:

- View all active schedules with their cron expression, goal, policy, next fire time, and last fire time
- Add a new schedule via the inline form
- Cancel a schedule with a single click

The panel auto-refreshes every 30 seconds and the REST endpoints are:

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/schedules` | List all schedules |
| `POST` | `/api/schedules` | Register a new schedule |
| `DELETE` | `/api/schedules/{id}` | Cancel a schedule |

---

## Limitations

- **In-process only** — the scheduler runs inside the JVM. In a multi-instance deployment, every instance will fire independently. Use a distributed lock or a dedicated scheduler node if exactly-once firing is required.
- **1-minute resolution** — the smallest cron interval is 1 minute (`* * * * *`). Sub-minute scheduling is not supported.
- **UTC only** — all fire times are calculated and stored in UTC. Timezone-aware cron is not yet supported.
- **No dynamic context** — the intent context is captured at registration time and replayed verbatim on every firing. Use a capability to fetch fresh data at execution time.
