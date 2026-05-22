---
id: nexora-saga
title: nexora-saga
sidebar_position: 10
---

# nexora-saga

Orchestration-based saga coordinator. When a plan fails mid-execution, the saga rolls back all completed compensable steps in reverse topological order.

## How it works

1. Each step can declare a `compensateCapabilityId` in its `StepDefinition`.
2. If the overall execution reaches a `FAILED` status, `SagaOrchestrator` collects all completed steps that declared a compensation capability.
3. Compensations run sequentially in reverse topological order — the step closest to the failure compensates first, back toward the root.
4. A failed compensation is logged and skipped; the remaining compensations still run.

## Enabling sagas

```java
NexoraEngine engine = NexoraEngine.builder()
    .withSagaEnabled(true)
    .build();
```

## Declaring compensation on a step

```java
new StepDefinition(
    "charge",
    "charge_card",
    goal -> goal.contains("order"),
    Map.of("orderId", InputBinding.fromContext("orderId")),
    "chargeResult",
    Set.of("validate"),
    null,
    Duration.ofSeconds(5),
    "refund_card"           // compensateCapabilityId
);
```

## Writing a compensation capability

The compensation capability receives the original step's resolved inputs plus the full `ExecutionContext`. Use these to undo the work precisely.

```java
public class RefundCardCapability implements Capability {

    @Override
    public CapabilityResult execute(CapabilityRequest request) {
        String orderId = (String) request.inputs().get("orderId");
        String chargeId = (String) request.context().getOutput("chargeResult");

        paymentClient.refund(chargeId);

        return CapabilityResult.success(Map.of("refunded", true));
    }
}
```

## Events

Subscribe to `CompensationStartedEvent`, `CompensationCompletedEvent`, and `CompensationFailedEvent` via `NexoraEngine.subscribe()` to observe rollback progress. See [nexora-event](nexora-event) for the full event reference.

## What sagas do not cover

- Compensations that fail are logged and skipped; the engine does not retry them by default.
- Sagas are sequential — if you need parallel compensation, implement that logic inside the compensation capability itself.
- Sagas do not lock resources. If your capability interacts with external systems that require distributed locks, manage that at the application level.
