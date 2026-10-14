---
id: execution-replay
title: Execution Replay
---

# Execution Replay

Replay re-runs a finished, unsuccessful execution from the start with the same goal and context. Use it to recover from a transient failure without rebuilding the intent by hand.

```java
ExecutionHandle replay = engine.replay(failedExecutionId);
replay.executionId();          // a new id; the original is untouched
replay.result().join();        // the replay's outcome
```

Replay needs a persistence store, since the original goal, context and step records are read from it.

---

## What gets replayed

- **Goal and context** are copied from the original. The replay's context also carries `_replayOf` with the original execution id (`NexoraEngine.REPLAY_OF_CONTEXT_KEY`).
- **New ids**: the replay gets its own `executionId` and `traceId`.
- **Defaults apply**: the original's per-intent deadline and webhook are not persisted, so the replay uses the engine-wide defaults.

## Which executions can be replayed

| Original state | Replay | Idempotency keys |
|----------------|--------|------------------|
| `FAILED`, `TIMED_OUT`, `CANCELLED` | Allowed | Steps that **completed** reuse their original key; other steps get a fresh key |
| `COMPENSATED` | Allowed | Every step gets a fresh key |
| `COMPLETED`, `RUNNING`, `COMPENSATING` | Rejected (`409`) | n/a |

An execution that is still finishing on this engine (including its saga compensation) is treated as `RUNNING`.

### Why keys are reused, and when they are not

Each capability request carries an `idempotencyKey`. Reusing the key for steps that already **completed** lets an idempotent capability, such as a payment API that dedupes by key, skip repeating a side effect that already happened.

Two cases deliberately get a fresh key:

- **Failed steps.** Providers that cache responses per key would return the same failure, so the replay could never succeed.
- **Every step of a `COMPENSATED` execution.** Compensation already rolled the work back (for example, refunded a charge). Reusing the key would make the provider return the cached "charged" response without charging again, and the money would never move.

Reuse only helps capabilities that honor `idempotencyKey`. A capability that ignores it simply runs again.

---

## REST API

```bash
curl -H "Authorization: Bearer $NEXORA_API_KEY" -X POST http://localhost:9464/api/executions/<execution-id>/replay
# 202 {"accepted":true,"executionId":"<new id>","replayOf":"<execution-id>"}
```

| Status | Meaning |
|--------|---------|
| `202` | Replay started; `executionId` is the new run |
| `404` | Unknown execution id |
| `409` | Not replayable; the body includes the current `state` |
| `503` | The observe server has no persistence store |

The observe UI's **Execution History** panel shows a **Replay** button on replayable rows.

> **Authentication**: this endpoint requires the observe server's API key. See [Authentication](authentication).

## CLI

```bash
nexora replay <execution-id>
nexora replay <execution-id> --server http://nexora.internal:9464
```

Like `nexora cancel`, this calls the observe server, so the replay runs on the engine that has the capabilities. It exits `0` when the replay started, `1` when the execution is unknown, not replayable, or the server is unreachable, and `2` for invalid arguments.

## Dead letter queue

Replaying a dead letter (`POST /api/dead-letters/{id}/replay`, `nexora dlq replay <id>`) goes through the same mechanism: the replay is tagged with `_replayOf` and reuses keys as above. If the original execution record no longer exists, the dead letter's own goal and context are used.

---

## Limits

Replay restarts from the first step. Step outputs are now stored ([#32](https://github.com/iamvirul/nexora/issues/32)), but resuming from the last successful step is not built yet. A truncated output (`outputTruncated: true`) cannot be restored, so a resume will have to run that step again.
