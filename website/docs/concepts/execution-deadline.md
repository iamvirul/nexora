# Execution Deadline (Unreleased version)

:::info Unreleased Version
This feature is implemented in the unreleased development version of Nexora.
:::

Nexora supports a **plan-level wall-clock execution deadline** (timeout) to guarantee that an execution terminates within an upper bound of time. 

Unlike the step-level timeouts configured on individual capability invocations, a plan-level execution deadline places a constraint on the entire goal execution lifecycle.

---

## How It Works

When a plan-level deadline is set (either globally or per-intent):
1. **Watchdog Activation**: An asynchronous virtual watchdog task is scheduled using `CompletableFuture.delayedExecutor()` running on virtual threads.
2. **Deadline Expiry**: If the plan execution does not complete naturally before the deadline, the watchdog fires.
3. **Execution Halting**: 
   - A `halted` flag is set to `true`.
   - Steps that have not yet started (still waiting for their dependencies) are suppressed and return a synthetic `TIMED_OUT` status immediately without calling their capability logic.
   - Currently running steps (already executing) are allowed to finish naturally (to avoid interrupting side effects in Virtual Threads).
4. **Terminal Status**: The plan completes with a terminal execution status of `TIMED_OUT`.
5. **Saga Compensation**: If orchestration-based saga is enabled (`.withSagaEnabled(true)`), any steps that did complete successfully before the deadline are compensated in reverse topological order.
6. **Events**: A `PlanTimedOutEvent` is published to the event bus.

---

## Configuration

### 1. Engine-Wide Default Deadline
You can configure a global plan deadline on the engine builder:

```java
NexoraEngine engine = NexoraEngine.builder()
    .withDefaultPlanDeadline(Duration.ofSeconds(5))
    .build();
```

### 2. Per-Intent Overrides
You can override or set a specific deadline for a single execution using the overloaded 3-argument execute method:

```java
// Sets a 2-second deadline specifically for this execution,
// overriding any engine-wide default.
CompletableFuture<ExecutionResult> future = engine.execute(
    "process order payment notification", 
    Map.of("orderId", "ORD-42"), 
    Duration.ofSeconds(2)
);
```

---

## CLI Demonstration

To demonstrate this feature via the CLI, use the `--timeout` option on the `demo` command:

```bash
java -jar nexora-cli/target/nexora.jar demo --timeout 50
```

This will run the demo with a 50ms plan-level deadline. Since the demo steps together take longer than 50ms, you will see the watchdog trigger:

```text
21:56:59.468 [virtual-40] WARN  c.n.runtime.engine.ExecutionEngine - Plan deadline expired executionId=eedbe380-0205-43f6-94b6-ddf0be193ac0 deadline=PT0.05S
21:56:59.470 [virtual-40] WARN  c.n.runtime.engine.ExecutionEngine - Execution timed out executionId=eedbe380-0205-43f6-94b6-ddf0be193ac0 elapsed=62ms deadline=PT0.05S

Result:   TIMED_OUT
Steps:    3 executed
Trace:    eedbe380-0205-43f6-94b6-ddf0be193ac0
```
