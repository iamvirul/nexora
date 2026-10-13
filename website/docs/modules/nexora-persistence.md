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
    void recordWebhookDelivery(WebhookDeliveryRecord record);
    List<WebhookDeliveryRecord> getWebhookDeliveries(String executionId);
    Optional<ExecutionRecord> findById(String executionId);
    List<ExecutionRecord> findRecent(int limit);

    // Readiness (default methods, report true unless overridden)
    default boolean isHealthy();        // backend reachable
    default boolean isSchemaCurrent();  // no failed migration, schema not older than expected

    // Execution history (default methods)
    default List<ExecutionRecord> findByFilter(ExecutionFilter filter); // summaries, newest first
    default long count(ExecutionFilter filter);                         // ignores cursor and limit

    // Dead Letter Queue (default methods)
    default void createDeadLetter(DeadLetterRecord record);
    default Optional<DeadLetterRecord> findDeadLetterById(String id);
    default List<DeadLetterRecord> findDeadLetters(DeadLetterReviewState state, int offset, int limit);
    default void updateDeadLetterState(String id, DeadLetterReviewState state, String resolveReason);

    // Cron scheduling (default methods)
    default void createSchedule(ScheduleRecord record);
    default Optional<ScheduleRecord> findScheduleById(String id);
    default List<ScheduleRecord> findActiveSchedules();
    default List<ScheduleRecord> findAllSchedules();
    default void updateScheduleLastFired(String id, Instant lastFiredAt, Instant nextFireAt);
    default void deactivateSchedule(String id);

    void close();
}
```

**Idempotency:** `upsertStep` must be safe to call twice with the same `(executionId, stepId, idempotencyKey)`. The JDBC implementation uses a standard SQL `MERGE ... USING` upsert, and an update that carries no idempotency key keeps the one recorded at step start.

The DLQ and scheduling methods are `default` methods that throw `UnsupportedOperationException`. Existing custom `ExecutionStore` implementations continue to compile without change. Override them to enable those features.

---

## JdbcExecutionStore

The bundled store. Defaults to embedded H2 (`JdbcExecutionStore.h2(path)`, `h2InMemory()`) and runs on PostgreSQL 15 or later through the same code, since all of its SQL is standard.

### Schema migrations

Flyway applies the migrations in `nexora-persistence/src/main/resources/db/nexora/migration/` when the store is constructed, before any query runs. History is kept in `nexora_schema_history`. Both names are specific to Nexora so they do not collide with an application's own Flyway setup on the same database.

- **Failures stop startup.** If a migration fails, or a migration that was already applied has been edited, the constructor throws `IllegalStateException` with Flyway's message, which names the script.
- **Existing stores are adopted.** A database created by a release before migrations existed has no history table. It is baselined at version 0, and `V1` creates only the tables it is missing, so existing data stays where it is.
- **Readiness.** `GET /health/ready` reports `migrations: DOWN` when a migration is recorded as failed or the schema is older than the one the store migrated to.
- **Connections.** Migrations hold two connections at once. A store that opens its own pool (`new JdbcExecutionStore(url)`, `h2(...)`, `h2InMemory()`) migrates over separate connections, so this needs nothing from you. If you pass your own `DataSource`, it must allow at least two connections.

See [CONTRIBUTING.md](https://github.com/iamvirul/nexora/blob/main/CONTRIBUTING.md#database-migrations) for how to name and write a migration.

---

## Connecting the CLI observer

The embedded HTTP server in `nexora-cli` serves execution history from the store. See [CLI Reference](../cli) for the `observe` command.

---

## PostgreSQL

Add the PostgreSQL JDBC driver (`org.postgresql:postgresql`) to your application, then either let the store own a pool:

```java
ExecutionStore store = new JdbcExecutionStore(
        "jdbc:postgresql://db:5432/nexora", PoolConfig.defaults());
```

or hand it a `DataSource` you already manage (at least two connections). The store does not close it:

```java
ExecutionStore store = new JdbcExecutionStore(dataSource);
```

Keep credentials out of the URL where you can. Error messages show only the URL's `jdbc:<subprotocol>` prefix, but a driver's own error text may include the full URL.

Register it on the engine:

```java
NexoraEngine engine = NexoraEngine.builder()
    .withExecutionStore(store)
    .build();
```

To back Nexora with a database that is not SQL, implement `ExecutionStore` yourself.

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
