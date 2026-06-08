---
id: dead-letter-queue
title: Dead Letter Queue
sidebar_position: 6
---

# Dead Letter Queue (Unreleased version)

:::info Unreleased Version
This feature is implemented in the unreleased development version of Nexora.
:::

When an execution fails after exhausting all retries, Nexora writes a record to the **dead letter queue** (DLQ) — the `nexora_dead_letters` table — and fires an `ExecutionDeadLetteredEvent` on the event bus. This gives operators a structured audit trail of every permanently failed execution and a way to replay or resolve them without querying the database directly.

---

## How It Works

1. **Step Failure** — a step fails and retries are exhausted; `DagStepScheduler` reports `ExecutionStatus.FAILED` to the engine.
2. **Dead Letter Write** — `ExecutionEngine` calls `ExecutionStore.createDeadLetter()` with the original `goal`, `context`, `failureCode`, and `failureMessage`.
3. **Event Fire** — `ExecutionDeadLetteredEvent` is published on the event bus so any subscriber can trigger alerting.
4. **Operator Action** — the operator uses the CLI or REST API to inspect, replay, or resolve the dead letter.

---

## Dead Letter Record Fields

| Field | Type | Description |
|-------|------|-------------|
| `id` | `String` (UUID) | Unique identifier for this dead letter record |
| `executionId` | `String` | The original failed execution ID |
| `goal` | `String` | The intent goal string |
| `context` | `Map<String, Object>` | The original intent context |
| `failureCode` | `String` | Machine-readable failure code (e.g. `STEP_FAILED`) |
| `failureMessage` | `String` | Human-readable error detail |
| `failedAt` | `Instant` | When the failure was recorded |
| `reviewState` | `DeadLetterReviewState` | `PENDING`, `RESOLVED`, or `REPLAYED` |
| `resolveReason` | `String` | Optional reason set on `RESOLVED` transitions |

---

## Subscribing to the Event

Subscribe to `ExecutionDeadLetteredEvent` for alerting or external notification:

```java
engine.subscribe(ExecutionDeadLetteredEvent.class, e -> {
    alerting.notify(String.format(
        "Execution dead-lettered executionId=%s code=%s",
        e.executionId(), e.failureCode()));
});
```

---

## CLI

```bash
# list all PENDING dead letters (default)
nexora dlq list

# list all states
nexora dlq list --state ALL

# list resolved entries, page 2
nexora dlq list --state RESOLVED --page 1 --size 10

# replay: creates a new execution with the same goal and context
nexora dlq replay <dead-letter-id>

# resolve: mark as handled, no replay needed
nexora dlq resolve <dead-letter-id> --reason "Root cause fixed in 1.2.3"
```

---

## REST API

All DLQ endpoints are served by the observability server (`nexora observe`).

> **Authentication**: Bearer token enforcement is deferred to [#30](https://github.com/iamvirul/nexora/issues/30).

### List dead letters

```bash
# default: PENDING, page 0, size 20
curl http://localhost:9464/api/dead-letters

# with filters
curl "http://localhost:9464/api/dead-letters?state=RESOLVED&page=0&size=10"
```

Response:

```json
{
  "items": [
    {
      "id": "a1b2c3d4-...",
      "executionId": "f1e2d3c4-...",
      "goal": "process order payment",
      "failureCode": "STEP_FAILED",
      "failureMessage": "Card declined",
      "failedAt": "2026-06-08T10:30:00Z",
      "reviewState": "PENDING"
    }
  ],
  "page": 0,
  "size": 20
}
```

### Replay a dead letter

Creates a new execution with the original goal and context, then transitions the dead letter to `REPLAYED`.

```bash
curl -X POST http://localhost:9464/api/dead-letters/<id>/replay
```

Returns `409 Conflict` if the dead letter is not in `PENDING` state.

### Resolve a dead letter

Marks the dead letter as `RESOLVED` with an optional reason.

```bash
curl -X POST http://localhost:9464/api/dead-letters/<id>/resolve \
  -H "content-type: application/json" \
  -d '{"reason": "investigated and closed — no retry needed"}'
```

---

## Implementing DLQ in a Custom Store

If you provide a custom `ExecutionStore` implementation, override the four default DLQ methods:

```java
@Override
public void createDeadLetter(DeadLetterRecord record) { /* INSERT */ }

@Override
public Optional<DeadLetterRecord> findDeadLetterById(String id) { /* SELECT */ }

@Override
public List<DeadLetterRecord> findDeadLetters(DeadLetterReviewState state, int offset, int limit) { /* SELECT with filter */ }

@Override
public void updateDeadLetterState(String id, DeadLetterReviewState state, String resolveReason) { /* UPDATE */ }
```

The default implementations throw `UnsupportedOperationException`, so existing custom stores continue to compile without change.
