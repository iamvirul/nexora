---
id: execution-cancellation
title: Execution Cancellation
---

# Execution Cancellation

A running execution can be stopped on request: from code with `NexoraEngine.cancel(executionId)`, over HTTP with `DELETE /api/executions/{executionId}` on the observe server, or from the terminal with `nexora cancel <executionId>`.

---

## How It Works

1. **Halt**: the cancel request flips the execution's halt signal to `CANCELLED`.
2. **Pending steps**: steps that have not started are never started. They are recorded with failure code `CANCELLED`.
3. **Running steps**: the threads running steps are interrupted. Interruptible blocking calls (`Thread.sleep`, `Object.wait`, `BlockingQueue.take`, `java.net.http.HttpClient.send`) throw `InterruptedException`. Steps behind a per-capability timeout are interrupted too.
4. **Retries stop**: an interrupted step does not start another retry attempt.
5. **Terminal state**: once in-flight steps return, the execution completes with `ExecutionStatus.CANCELLED`, is persisted as `CANCELLED`, and `PlanCancelledEvent` is published. No dead letter is written, because the stop was deliberate.
6. **Saga**: with `withSagaEnabled(true)`, completed steps that declare a compensating capability are compensated, exactly as for a failure or timeout. The persisted state then moves on to `COMPENSATING` and `COMPENSATED`.

The plan [deadline](./execution-deadline) uses the same halt signal with one difference: a deadline suppresses pending steps but lets running steps finish, while a cancel interrupts them.

### Writing capabilities that cancel cleanly

Cancellation is cooperative, as with all Java thread interruption. A capability that blocks should let `InterruptedException` end its work and restore the flag:

```java
try {
    Response response = client.send(request);   // interruptible blocking call
    return CapabilityResult.success(response.body());
} catch (InterruptedException e) {
    Thread.currentThread().interrupt();
    return CapabilityResult.failure("INTERRUPTED", "cancelled");
}
```

A capability that ignores interrupts keeps running until it returns on its own. The execution still finishes as `CANCELLED`, but only after that step returns.

---

## Java API

Use `submit` to get the execution id up front; `execute` only reveals it once the run finishes.

```java
ExecutionHandle handle = engine.submit(intent);   // returns immediately

boolean cancelled = engine.cancel(handle.executionId()).join();
// true:  the execution is being cancelled (also returned for a repeat cancel)
// false: it already finished, or its deadline already fired

ExecutionResult result = handle.result().join();  // status() == CANCELLED
```

`cancel` returns once the request is accepted. Wait on `handle.result()` to know when the execution has fully stopped.

| Situation | Result |
|-----------|--------|
| Running on this engine | `true` |
| Already being cancelled | `true` |
| Finished, or stopping on its deadline | `false` |
| Unknown id | fails with `ExecutionNotFoundException` |
| `RUNNING` in the store but not on this engine (another instance, or left behind by a crashed process) | fails with `ExecutionNotOwnedException` |
| Blank id | fails with `IllegalArgumentException` |

Without a persistence store the engine forgets executions once they finish, so cancelling a finished execution reports `ExecutionNotFoundException` instead of `false`.

Subscribe to the event for alerting or auditing:

```java
engine.subscribe(PlanCancelledEvent.class, e ->
        log.info("cancelled executionId={} after {}ms", e.executionId(), e.elapsed().toMillis()));
```

---

## REST API

`POST /api/execute` returns the id of the execution it started:

```bash
curl -X POST http://localhost:9464/api/execute \
  -H "content-type: application/json" \
  -d '{"goal":"process payment","context":{"orderId":"ORD-1"}}'
# 202 {"accepted":true,"goal":"process payment","executionId":"4f3a1c2d-...","message":"Execution accepted"}

curl -X DELETE http://localhost:9464/api/executions/4f3a1c2d-...
```

The observe UI shows a **Cancel** button on every running execution, which calls the same endpoint.

| Status | Body | Meaning |
|--------|------|---------|
| `200` | `{"cancelled":true,"executionId":"..."}` | Cancelled, or already being cancelled |
| `404` | `{"error":"Execution not found","executionId":"..."}` | Unknown id |
| `409` | `{"error":"Execution has already finished","executionId":"..."}` | Already terminal |
| `409` | `{"error":"Execution is not running on this engine instance","executionId":"..."}` | Owned by another instance |

> **Warning**: This endpoint currently has no caller authentication. Do not expose the observe server to untrusted networks. Authentication will be enforced once [#30](https://github.com/iamvirul/nexora/issues/30) lands.

---

## CLI

```bash
nexora cancel <execution-id>
nexora cancel <execution-id> --server http://nexora.internal:9464
```

Executions live in the memory of the process that started them, so `nexora cancel` calls the observe server's REST endpoint rather than building its own engine. It exits `0` when the cancel was accepted, `1` when the execution is unknown, already finished, or the server is unreachable, and `2` for invalid arguments.

---

## Try it in the demo

The payment pipeline sample (`tests/payment-pipeline`) runs a cancellation scenario at startup and adds a **Slow payment** button to its UI. It fills the form with `"slowUpstreamMs": 30000`, which makes the profile lookup hang. Press **Run**, then **Cancel** on the running execution: the hung step is interrupted, the steps that already finished are compensated, and the execution ends as `CANCELLED`.

```bash
mvn -q install -DskipTests
java -jar tests/payment-pipeline/target/payment-pipeline.jar
# open http://localhost:9464/
```

---

## Metrics

Cancelled executions increment `nexora_plan_cancelled_total` and are observed in `nexora_plan_duration_seconds{status="cancelled"}`. They do not count toward `nexora_plan_failed_total`, so cancels never trip failure-rate alerts.
