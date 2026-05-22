---
id: nexora-api
title: nexora-api
sidebar_position: 14
---

# nexora-api

`NexoraEngine` — the public facade. This is the only class you need to import for production use.

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
    .withExecutionStore(new PostgresExecutionStore(dataSource))

    // Saga rollback
    .withSagaEnabled(true)

    // Custom executor (default: virtual threads)
    .withExecutor(Executors.newVirtualThreadPerTaskExecutor())

    // Plugin directory (loads + activates all JARs in the directory)
    .withPluginDirectory(Path.of("/etc/nexora/plugins"))

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
NexoraEngine.HealthSnapshot snap = engine.contractHealth("charge_card");
// snap.sampleCount(), snap.errorRate(), snap.p99Latency()
```

---

## Plugin management at runtime

```java
engine.loadPlugin(Path.of("/opt/plugins/v2-plugin.jar"));
engine.activatePlugin("v2-plugin");
engine.deactivatePlugin("v1-plugin");
```

---

## Shutdown

Always close the engine to drain in-flight executions and shut down plugins cleanly:

```java
try (NexoraEngine engine = NexoraEngine.builder()...build()) {
    engine.execute(intent).join();
} // shutdown() called automatically
```

Or explicitly:

```java
engine.shutdown();
```

Shutdown waits for all in-flight executions to complete before closing plugin class loaders and the execution store.
