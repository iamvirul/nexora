---
id: nexora-retry
title: nexora-retry
sidebar_position: 4
---

# nexora-retry

Retry policies for capability executions. The `RetryInterceptor` consults the policy registry before each re-attempt.

## RetryPolicy interface

```java
public interface RetryPolicy {
    boolean shouldRetry(int attemptsMade, Throwable cause);
    Duration backoffDelay(int attemptsMade);
}
```

Two built-in implementations:

| Class | Behaviour |
|---|---|
| `NoRetryPolicy` | Never retries. Default when no policy is configured. |
| `ExponentialBackoffPolicy` | Exponential delay with ±25% jitter. |

---

## ExponentialBackoffPolicy

```java
RetryPolicy policy = ExponentialBackoffPolicy.builder()
    .maxAttempts(4)
    .initialDelay(Duration.ofMillis(100))
    .multiplier(2.0)
    .maxDelay(Duration.ofSeconds(8))
    .retryOn(IOException.class, TimeoutException.class)
    .build();
```

| Parameter | Default | Description |
|---|---|---|
| `maxAttempts` | `3` | Total attempts including the first call |
| `initialDelay` | `200 ms` | Delay before attempt 2 |
| `multiplier` | `2.0` | Multiplied each retry (must be > 1.0) |
| `maxDelay` | `10 s` | Upper bound on delay between retries |
| `retryOn` | all exceptions | Restrict retries to specific exception types |

The ±25% jitter prevents retry storms when many concurrent executions fail simultaneously.

---

## RetryPolicyRegistry

Maps named policy IDs to `RetryPolicy` instances. Referenced by `StepDefinition.retryPolicyId`.

```java
NexoraEngine engine = NexoraEngine.builder()
    .withDefaultRetryPolicy(
        ExponentialBackoffPolicy.builder()
            .maxAttempts(3)
            .initialDelay(Duration.ofMillis(200))
            .build()
    )
    .build();
```

The default retry policy applies to any step that does not declare a `retryPolicyId`. To disable retries globally, pass `RetryPolicy.noRetry()`.

---

## Per-step retry policies

Override retry behaviour on a per-step basis via `StepDefinition`:

```java
new StepDefinition(
    "charge",
    "charge_card",
    goal -> goal.contains("order"),
    inputs,
    "chargeResult",
    Set.of("validate"),
    "payment-retry-policy",   // matches a named policy in the registry
    Duration.ofSeconds(5)     // per-step timeout
)
```
