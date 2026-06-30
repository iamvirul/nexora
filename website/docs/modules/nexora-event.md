---
id: nexora-event
title: nexora-event
sidebar_position: 5
---

# nexora-event

Sealed event hierarchy and in-process event bus. Subscribe to observe execution lifecycle events without coupling to internal engine state.

## Event types

All events extend `ExecutionEvent`.

### Plan lifecycle

| Event | Fired when |
|---|---|
| `PlanStartedEvent` | Engine begins executing a plan |
| `PlanCompletedEvent` | All steps finished successfully |
| `PlanFailedEvent` | One or more steps failed and execution halted |
| `PlanAmendedEvent` | A step returned an amendment (add/skip/modify) |

### Step lifecycle

| Event | Fired when |
|---|---|
| `StepStartedEvent` | A step begins execution |
| `StepCompletedEvent` | A step finishes successfully |
| `StepFailedEvent` | A step throws or returns a failure result |

### Saga compensation

| Event | Fired when |
|---|---|
| `CompensationStartedEvent` | Saga rollback begins for a step |
| `CompensationCompletedEvent` | A compensation capability succeeded |
| `CompensationFailedEvent` | A compensation capability failed (execution continues for remaining compensations) |

### Plugin lifecycle

| Event | Fired when |
|---|---|
| `PluginActivatedEvent` | A plugin successfully initializes |
| `PluginDeactivatedEvent` | A plugin shuts down |

### Cron scheduling (Unreleased)

| Event | Fired when |
|---|---|
| `ScheduledExecutionFiredEvent` | A schedule fires and the intent is dispatched to the engine |
| `ScheduledExecutionMissedEvent` | A schedule had windows that were missed during downtime (only when policy is `SKIP`) |

`ScheduledExecutionFiredEvent` carries `scheduleId()`, `executionId()`, and `firedAt()`.  
`ScheduledExecutionMissedEvent` carries `scheduleId()`, `missedCount()`, `windowStart()`, and `windowEnd()`.

---

## Subscribing to events

Subscribe via `NexoraEngine`:

```java
NexoraEngine engine = NexoraEngine.builder()
    .withPlugin(myPlugin)
    .build();

// Subscribe before executing
Subscription sub = engine.subscribe(event -> {
    switch (event) {
        case PlanStartedEvent e ->
            log.info("Plan started: executionId={}", e.executionId());
        case StepFailedEvent e ->
            metrics.increment("step.failed", "capability", e.capabilityId());
        case PlanAmendedEvent e ->
            log.debug("Amendment: type={} step={}", e.amendmentType(), e.stepId());
        default -> {}
    }
});

// Unsubscribe when done
sub.cancel();
```

---

## InProcessEventBus

The default `ExecutionEventBus` implementation. Dispatches events synchronously on the calling thread. Subscriber exceptions are caught and logged; they do not affect execution.

Provide a custom `ExecutionEventBus` implementation to fan out to Kafka, a metrics pipeline, or any external system:

```java
public class KafkaEventBus implements ExecutionEventBus {
    @Override
    public Subscription subscribe(EventHandler<ExecutionEvent> handler) { ... }

    @Override
    public void publish(ExecutionEvent event) {
        kafkaProducer.send(serialize(event));
    }
}
```
