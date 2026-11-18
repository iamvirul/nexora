---
id: nexora-api
title: nexora-api
sidebar_position: 14
---

# nexora-api

`NexoraEngine`, the public facade. This is the only class you need to import for production use.

## Builder reference

```java
NexoraEngine engine = NexoraEngine.builder()
    // Capabilities and plugins
    .withPlugin(new PaymentPlugin())
    .withPlugin(new NotificationPlugin())

    // Step definitions (if not contributed by plugins)
    .withStepDefinition(new StepDefinition("validate", "validate_order", ...))

    // Retry behaviour
    .withDefaultRetryPolicy(
        ExponentialBackoffPolicy.builder()
            .maxAttempts(3)
            .initialDelay(Duration.ofMillis(200))
            .build()
    )

    // Per-step default timeout (overridden by StepDefinition.timeout)
    .withDefaultTimeout(Duration.ofSeconds(30))

    // Distributed tracing
    .withTracer(new OtelTracer(GlobalOpenTelemetry.get()))

    // Execution persistence
    .withExecutionStore(new JdbcExecutionStore(dataSource))

    // Saga rollback
    .withSagaEnabled(true)

    // Custom executor (default: virtual threads)
    .withExecutor(Executors.newVirtualThreadPerTaskExecutor())

    // Plugin JARs (loaded + activated in build(), after withPlugin() plugins, in the order added)
    .withPluginJar(Path.of("/etc/nexora/plugins/payment-plugin-1.2.0.jar"))

    // Graceful shutdown (defaults: 15s drain, JVM shutdown hook registered)
    .withShutdownDrainTimeout(Duration.ofSeconds(15))
    .withShutdownHook(true)

    .build();
```

---

## Executing an intent

```java
Intent intent = new Intent("process_order", Map.of(
    "orderId",    "ORD-991",
    "customerId", "CUST-42"
));

ExecutionResult result = engine.execute(intent).join();

if (result.status() == ExecutionStatus.COMPLETED) {
    Object receipt = result.outputFor("send_receipt"); // step output by key
} else {
    result.stepResults().stream()
          .filter(s -> s.status() == StepStatus.FAILED)
          .forEach(s -> log.error("Step {} failed: {}", s.stepId(), s.error()));
}
```

---

## Subscribing to events

```java
Subscription sub = engine.subscribe(event -> {
    if (event instanceof StepFailedEvent e) {
        alerting.fire("step.failed", e.capabilityId());
    }
});

// Later:
sub.cancel();
```

---

## Inspecting contract health

```java
NexoraEngine.HealthSnapshot snap = NexoraEngine.HealthSnapshot.from(engine.capabilityHealth("charge_card"));
// snap.state(), snap.sampleCount(), snap.errorRate(), snap.p99Latency()
```

---

## Plugin management at runtime

```java
// Loads the JAR and activates the plugin with the given id
engine.loadPlugin(Path.of("/opt/plugins/v2-plugin.jar"), "v2-plugin");

engine.activePluginIds(); // [..., "v2-plugin"]
```

All active plugins are deactivated when the engine shuts down (`engine.shutdown()` or `close()`). There is no public API to deactivate a single plugin at runtime.

---

## Cancelling an execution

```java
ExecutionHandle handle = engine.submit(intent);   // id is known immediately

engine.cancel(handle.executionId()).join();      // true if cancelled, false if already finished
handle.result().join().status();                 // CANCELLED
```

Pending steps never start and running steps are interrupted. Unknown ids fail with `ExecutionNotFoundException`. See [Execution Cancellation](../concepts/execution-cancellation) for the full reference.

---

## Replaying an execution

```java
ExecutionHandle replay = engine.replay(failedExecutionId); // new id, same goal and context
replay.result().join();
```

Allowed for `FAILED`, `TIMED_OUT`, `CANCELLED` and `COMPENSATED` executions. Steps that completed reuse their idempotency keys unless the original was compensated. See [Execution Replay](../concepts/execution-replay).

---

## Cron scheduling

Requires a persistence store. Returns a `ScheduledExecution` handle with `id()`, `nextFireTime()`, and `cancel()`.

```java
// Register, default FIRE_ONCE missed-fire policy
ScheduledExecution handle = engine.schedule("0 0 * * *", new Intent("nightly cleanup", Map.of()));

System.out.println(handle.nextFireTime()); // next UTC midnight

// Override missed-fire policy
engine.schedule("*/5 * * * *", intent, MissedFirePolicy.SKIP);

// Cancel
handle.cancel();
engine.cancelSchedule(handle.id()); // equivalent

// Query
List<ScheduleRecord> active = engine.listActiveSchedules();
List<ScheduleRecord> all    = engine.listSchedules();
```

See [Cron Scheduling](../concepts/cron-scheduling) for the full reference.

---

## Shutdown

Shutdown drains in-flight executions instead of abandoning them:

1. New executions, replays and cron firings are rejected with `EngineShuttingDownException`, and `readiness()` reports the `executor` check DOWN so a load balancer stops routing to the instance. The HTTP API answers `POST /api/execute` and replays with `503`.
2. In-flight executions get up to the drain timeout to finish on their own.
3. Any still running are cancelled as by `cancel()`: running steps are interrupted and, with saga enabled, completed steps are compensated. Shutdown waits up to the drain timeout again for that, so it takes at most twice the drain timeout.
4. An `EngineShutdownEvent` with the drain counts is published.
5. Plugins are deactivated and the execution store and tracer are closed.

```java
try (NexoraEngine engine = NexoraEngine.builder()...build()) {
    engine.execute(intent).join();
} // close() calls shutdown() with the configured drain timeout

// Or with an explicit drain timeout; Duration.ZERO cancels in-flight work at once
DrainResult drain = engine.shutdown(Duration.ofSeconds(10));
if (!drain.clean()) {
    log.warn("{} executions did not finish", drain.unfinished());
}
```

`DrainResult` and `EngineShutdownEvent` report `inFlight`, `completed` (finished within the timeout), `cancelled` (cancelled after it), `unfinished` (still running when shutdown stopped waiting, for example a step that ignores interrupts) and `elapsed`. A cancelled execution ends `CANCELLED`, or `COMPENSATED` with saga, and can be [replayed](../concepts/execution-replay) on another instance.

Only the first call shuts down; later and concurrent calls wait for it and return the same result.

By default `build()` registers a JVM shutdown hook that calls `shutdown()`, so SIGTERM drains the engine too. Turn it off with `withShutdownHook(false)` when a container such as Spring already closes the engine on exit. During JVM shutdown an asynchronous subscriber may not get to run before the process exits; use `subscribeSync(EngineShutdownEvent.class, ...)` to be sure of seeing the event.

The default drain timeout of 15s keeps the whole shutdown within the 30s Kubernetes `terminationGracePeriodSeconds`. If you raise it, raise the grace period to more than twice the drain timeout.
