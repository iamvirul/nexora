---
id: nexora-executor
title: nexora-executor
sidebar_position: 6
---

# nexora-executor

DAG scheduler, interceptor pipeline, and capability contract monitor. This module is the execution heart of Nexora.

## DagStepScheduler

Turns a `Plan` into concurrent execution. Steps without dependencies run in parallel; dependent steps are queued until their predecessors complete.

**How it works:**

1. Compute the dependency graph from `Step.dependsOn`.
2. Identify all steps with no unmet dependencies and submit them to the `Executor`.
3. When a step completes, apply any `PlanAmendment` entries atomically, then release newly unblocked steps.
4. Repeat until the queue is empty or a non-retryable failure occurs.

Amendment processing is atomic with the parent step's completion — there is no window in which a newly injected step can be missed by the scheduler.

```java
// Internal wiring — you do not construct this directly.
// Use NexoraEngine.builder() which wires it for you.
DagStepScheduler scheduler = new DagStepScheduler(
    capabilityRegistry,
    retryPolicyRegistry,
    interceptorPipeline,
    contractMonitor,
    eventBus,
    sagaOrchestrator,
    executor
);

CompletableFuture<ExecutionResult> result = scheduler.execute(plan, context);
```

---

## ExecutionInterceptor

Chain-of-responsibility applied to every capability invocation. Built-in interceptors:

| Interceptor | What it does |
|---|---|
| `TracingInterceptor` | Opens/closes a tracing span around the invocation |
| `TimeoutInterceptor` | Enforces `Step.timeout`; throws `StepTimeoutException` if exceeded |
| `RetryInterceptor` | Re-invokes on failure according to the step's `RetryPolicy` |

The pipeline runs in this order: **Tracing → Timeout → Retry → Capability**.

### Adding a custom interceptor

```java
public class AuditInterceptor implements ExecutionInterceptor {

    @Override
    public CapabilityResult intercept(CapabilityRequest request, InterceptorChain chain) {
        auditLog.before(request.capabilityId(), request.inputs());
        CapabilityResult result = chain.proceed(request);
        auditLog.after(request.capabilityId(), result.status());
        return result;
    }
}

NexoraEngine engine = NexoraEngine.builder()
    .withInterceptor(new AuditInterceptor())  // prepended before built-ins
    .build();
```

Interceptors must be **thread-safe**; they are shared across all concurrent executions.

---

## CapabilityContractMonitor

Tracks per-capability health using a sliding window of recent call outcomes (default window: last 50 calls).

Before each invocation, `CapabilityInvoker` asks `isHealthy()`. If the capability has breached its declared contract (p99 latency or error rate), the invoker routes the call to the fallback capability instead.

**Minimum sample threshold:** 5 calls must be recorded before the monitor will declare a capability unhealthy, preventing false positives on cold starts.

```java
// Declare a contract on the capability:
@Override
public CapabilityContract contract() {
    return CapabilityContract.builder()
        .expectedP99Latency(Duration.ofMillis(150))
        .maxErrorRate(0.05)              // 5%
        .fallbackCapabilityId("slow_path")
        .build();
}
```

Inspect current health via `NexoraEngine.contractHealth(capabilityId)`:

```java
NexoraEngine.HealthSnapshot snap = engine.contractHealth("charge_card");
log.info("samples={} errorRate={} p99={}ms",
    snap.sampleCount(), snap.errorRate(), snap.p99Latency().toMillis());
```
