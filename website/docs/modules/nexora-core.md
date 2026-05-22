---
id: nexora-core
title: nexora-core
sidebar_position: 2
---

# nexora-core

Pure domain types. No I/O, no framework dependencies. Every other module depends on this one.

## Key types

### Intent

Represents a high-level goal submitted to the engine.

```java
// String goal + arbitrary context map
Intent intent = new Intent("process_order", Map.of(
    "orderId", "ORD-991",
    "customerId", "CUST-42"
));
```

| Field | Type | Description |
|---|---|---|
| `goal` | `String` | Identifies which step definitions should be selected by the planner |
| `context` | `Map<String, Object>` | Arbitrary key/value data available to all steps |

---

### Plan and Step

A `Plan` is an ordered list of `Step` objects produced by the planner.

```java
Plan plan = new Plan(List.of(
    new Step("validate", "validate_order", dependsOn, inputs, ...),
    new Step("charge",   "charge_card",    Set.of("validate"), inputs, ...)
));
```

**Step fields**

| Field | Type | Description |
|---|---|---|
| `id` | `String` | Unique within the plan |
| `capabilityId` | `String` | Which registered capability to invoke |
| `dependsOn` | `Set<String>` | Step IDs that must complete before this one starts |
| `inputs` | `Map<String, InputBinding>` | Static values or references to prior step outputs |
| `outputKey` | `String` | Key under which this step's output is stored in the execution context |
| `retryPolicyId` | `String` | References a policy in the `RetryPolicyRegistry` |
| `timeout` | `Duration` | Per-step deadline; triggers `TimeoutInterceptor` |
| `compensateCapabilityId` | `String` | Capability to call during saga rollback |

---

### InputBinding

Describes where a step's input value comes from.

```java
// Static literal value
InputBinding.literal("USD")

// Reference to a previous step's output
InputBinding.fromStep("fetch_user", "email")
```

---

### PlanAmendment

Sealed hierarchy. A step returns amendments in its `CapabilityResult` to reshape the remaining plan at runtime.

| Type | Effect |
|---|---|
| `AddStepAmendment` | Injects a new step before a named anchor step |
| `SkipStepAmendment` | Marks a pending step as skipped |
| `ModifyInputAmendment` | Overrides an input on a pending step |

```java
// Inside a capability's execute():
return CapabilityResult.success(output, List.of(
    new AddStepAmendment(newStep, "send_receipt"),
    new ModifyInputAmendment("send_receipt", "email", userEmail)
));
```

---

### ExecutionResult and StepResult

`ExecutionResult` is returned by `NexoraEngine.execute()`.

```java
ExecutionResult result = engine.execute(intent).join();

result.status();                       // COMPLETED | FAILED | PARTIAL
result.stepResults();                  // List<StepResult>
result.stepResults().stream()
      .filter(s -> s.status() == StepStatus.FAILED)
      .forEach(s -> log.error(s.error().getMessage()));
```

---

### ExecutionContext

Thread-safe context passed to every capability at runtime. Carries the trace context, execution ID, and the accumulated outputs of completed steps.

```java
public CapabilityResult execute(CapabilityRequest request) {
    String execId  = request.context().getExecutionId();
    String traceId = request.context().getTraceContext().traceId();
    Object prevOut = request.context().getOutput("fetch_user"); // prior step output
    ...
}
```
