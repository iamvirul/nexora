---
id: nexora-persistence
title: nexora-persistence
sidebar_position: 11
---

# nexora-persistence

Durable execution state storage. The default implementation uses H2 over JDBC. Swap it out for any SQL or NoSQL backend by implementing `ExecutionStore`.

## ExecutionStore interface

```java
public interface ExecutionStore extends AutoCloseable {
    void createExecution(ExecutionRecord record);
    void updateExecution(String executionId, ExecutionState state, Instant completedAt);
    void upsertStep(String executionId, StepRecord record);
    Optional<ExecutionRecord> findById(String executionId);
    List<ExecutionRecord> findRecent(int limit);
    void close();
}
```

**Idempotency:** `upsertStep` must be safe to call twice with the same `(executionId, stepId, idempotencyKey)`. The JDBC implementation uses an `INSERT OR REPLACE` (H2) / `ON CONFLICT DO UPDATE` (PostgreSQL) strategy.

---

## Default H2 store

Enabled automatically when no custom store is configured. Uses an in-process H2 database with Flyway-managed schema. Suitable for local development and testing.

Schema is located at `nexora-persistence/src/main/resources/db/migration/`.

---

## Connecting the CLI observer

The embedded HTTP server in `nexora-cli` serves execution history from the store. See [CLI Reference](../cli) for the `observe` command.

---

## PostgreSQL example

Add a JDBC driver and implement `ExecutionStore`:

```java
public class PostgresExecutionStore implements ExecutionStore {

    private final DataSource ds;

    @Override
    public void createExecution(ExecutionRecord record) {
        try (var conn = ds.getConnection();
             var ps = conn.prepareStatement(
                 "INSERT INTO executions(id, goal, state, started_at) VALUES (?,?,?,?)")) {
            ps.setString(1, record.executionId());
            ps.setString(2, record.goal());
            ps.setString(3, record.state().name());
            ps.setObject(4, record.startedAt());
            ps.executeUpdate();
        }
    }

    // ... implement remaining methods

    @Override
    public void close() { /* close data source if owned */ }
}
```

Register it on the engine:

```java
NexoraEngine engine = NexoraEngine.builder()
    .withExecutionStore(new PostgresExecutionStore(dataSource))
    .build();
```

---

## ExecutionRecord fields

| Field | Type | Description |
|---|---|---|
| `executionId` | `String` | UUID assigned at execution start |
| `goal` | `String` | The intent goal string |
| `state` | `ExecutionState` | `RUNNING`, `COMPLETED`, `FAILED` |
| `startedAt` | `Instant` | Execution start timestamp |
| `completedAt` | `Instant` | Execution end timestamp (null if still running) |
| `stepRecords` | `List<StepRecord>` | One entry per executed step |
