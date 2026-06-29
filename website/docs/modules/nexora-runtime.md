---
id: nexora-runtime
title: nexora-runtime
sidebar_position: 13
---

# nexora-runtime

`ExecutionEngine`, ties the planner and scheduler together into a single execution unit. You do not use this directly; `NexoraEngine` (in `nexora-api`) is the public entry point.

## Responsibilities

1. Accept an `Intent` and pass it to the `CompositePlanner` to produce a `Plan`.
2. Create an `ExecutionContext` with a new `traceId` and `executionId`.
3. Persist an `ExecutionRecord` via the `ExecutionStore` (if configured).
4. Hand the `Plan` and context to the `DagStepScheduler`.
5. On completion (success or failure), update the persisted record and fire a `PlanCompleted/Failed` event.

## Execution flow

```
Intent
  └─ CompositePlanner.plan()
        └─ Plan
              └─ DagStepScheduler.execute()
                    ├─ Step A (no deps)   ──► CapabilityInvoker ──► InterceptorPipeline
                    ├─ Step B (no deps)   ──► CapabilityInvoker ──► InterceptorPipeline
                    └─ Step C (deps: A,B) ──► (waits) ──► CapabilityInvoker
                          └─ ExecutionResult
```

## Thread model

By default, the engine uses `Executors.newVirtualThreadPerTaskExecutor()` (Java 21 virtual threads). Each step runs on its own virtual thread, allowing thousands of concurrent steps with minimal OS thread overhead.

Override the executor to use a platform thread pool or a custom thread factory:

```java
NexoraEngine engine = NexoraEngine.builder()
    .withExecutor(Executors.newFixedThreadPool(20))
    .build();
```

## Async execution

`NexoraEngine.execute()` returns a `CompletableFuture<ExecutionResult>`. The future completes when the entire plan finishes (or fails).

```java
engine.execute(intent)
      .thenAccept(result -> log.info("status={}", result.status()))
      .exceptionally(ex -> { log.error("Execution failed", ex); return null; });
```

Use `.join()` for synchronous callers, but be aware this will block the calling thread.

## Cron scheduler (Unreleased)

`nexora-runtime` also hosts `CronScheduler`, an in-process scheduler backed by a daemon `ScheduledExecutorService`. It is created automatically by `NexoraEngine.build()` when an `ExecutionStore` is configured.

On startup it calls `store.findActiveSchedules()`, applies the configured `MissedFirePolicy` for any schedule whose `next_fire_at` is in the past, then enqueues each wakeup at the correct future instant. After each firing it updates `last_fired_at` / `next_fire_at` in the store and re-enqueues the next occurrence.

You do not interact with `CronScheduler` directly, use `NexoraEngine.schedule(...)` and the returned `ScheduledExecution` handle. See [Cron Scheduling](../concepts/cron-scheduling).
