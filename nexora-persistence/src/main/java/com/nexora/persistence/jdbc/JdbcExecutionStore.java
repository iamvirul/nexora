package com.nexora.persistence.jdbc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexora.persistence.DeadLetterRecord;
import com.nexora.persistence.DeadLetterReviewState;
import com.nexora.persistence.ExecutionCursor;
import com.nexora.persistence.ExecutionFilter;
import com.nexora.persistence.ExecutionRecord;
import com.nexora.persistence.ExecutionState;
import com.nexora.persistence.ExecutionStore;
import com.nexora.persistence.MissedFirePolicy;
import com.nexora.persistence.ScheduleRecord;
import com.nexora.persistence.ScheduleStatus;
import com.nexora.persistence.StepRecord;
import com.nexora.persistence.StepState;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JDBC-backed ExecutionStore. Ships with H2 as the default embedded database.
 * SQL is standard (MERGE ... USING for upserts), so it also runs on PostgreSQL 15+; older
 * PostgreSQL servers are rejected at construction, before anything is migrated.
 *
 * Schema: Flyway applies the migrations under {@code db/nexora/migration} during construction,
 * before any query; a failed migration fails construction. Migrations are versioned in
 * {@code nexora_schema_history}, separate from any history table the host application keeps.
 *
 * Connection management: every call borrows a connection from a {@link DataSource} and returns
 * it before the call ends, so calls run in parallel up to the pool size. A call never holds more
 * than one connection at a time; nested lookups reuse the caller's connection. Holding two would
 * let N concurrent callers each take one and then wait forever for a second from a pool of size N.
 */
public final class JdbcExecutionStore implements ExecutionStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcExecutionStore.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    // Kept well under typical k8s probe timeouts so a stalled DB reports DOWN instead of hanging the probe.
    private static final int HEALTH_CHECK_TIMEOUT_SECONDS = 2;
    // SQLSTATE for a unique or primary key violation; H2 and PostgreSQL both report it.
    private static final String UNIQUE_VIOLATION = "23505";
    // Once the racing insert has committed the row exists, so one retry always reaches WHEN MATCHED.
    private static final int MERGE_ATTEMPTS = 2;

    private final DataSource dataSource;
    /** The pool this store opened itself and must close; null when the caller owns the DataSource. */
    private final HikariDataSource ownedPool;

    private final SchemaMigrator schema;

    /**
     * Uses a caller-managed DataSource. {@link #close()} leaves it open, since the caller may
     * share it with other components. Migrations borrow two of its connections at once.
     *
     * @throws IllegalArgumentException if the DataSource is a HikariCP pool of fewer than 2 connections
     * @throws IllegalStateException if the database is unsupported or a schema migration fails
     */
    public JdbcExecutionStore(DataSource dataSource) {
        this(dataSource, SchemaMigrator.DEFAULT_LOCATION);
    }

    /** Migrates from {@code migrationLocation} instead of the shipped migrations; lets tests supply broken ones. */
    JdbcExecutionStore(DataSource dataSource, String migrationLocation) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.ownedPool = null;
        DatabaseCompatibility.require(dataSource);
        this.schema = SchemaMigrator.migrate(dataSource, migrationLocation);
    }

    /** Single-connection store, for embedded H2 and tests. Calls are serialised on that connection. */
    public JdbcExecutionStore(String jdbcUrl) {
        this(jdbcUrl, PoolConfig.singleConnection());
    }

    /**
     * Opens and owns a HikariCP pool against {@code jdbcUrl}; {@link #close()} shuts it down.
     *
     * @throws IllegalStateException if the database is unreachable or unsupported, or a schema migration fails
     */
    public JdbcExecutionStore(String jdbcUrl, PoolConfig poolConfig) {
        HikariDataSource pool = HikariCPDataSourceFactory.create(jdbcUrl, poolConfig);
        this.dataSource = pool;
        this.ownedPool = pool;
        try {
            DatabaseCompatibility.require(pool);
            // Migrate over separate connections: Flyway needs two at once, more than a single-connection pool has.
            this.schema = SchemaMigrator.migrate(pool.getJdbcUrl(), SchemaMigrator.DEFAULT_LOCATION);
        } catch (RuntimeException e) {
            pool.close();
            throw e;
        }
    }

    /** Convenience factory for the default embedded H2 store at the given file path. */
    public static JdbcExecutionStore h2(String filePath) {
        return new JdbcExecutionStore("jdbc:h2:" + filePath + ";AUTO_SERVER=FALSE");
    }

    /** In-memory H2 — useful for tests; data is lost when connection closes. */
    public static JdbcExecutionStore h2InMemory() {
        return new JdbcExecutionStore("jdbc:h2:mem:nexora_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
    }

    @Override
    public void createExecution(ExecutionRecord record) {
        String sql = """
            MERGE INTO nexora_executions AS t
            USING (VALUES (CAST(? AS VARCHAR(36)), CAST(? AS VARCHAR(64)), CAST(? AS TEXT), CAST(? AS TEXT),
                           CAST(? AS VARCHAR(30)), CAST(? AS TIMESTAMP), CAST(? AS TIMESTAMP)))
                AS s (execution_id, trace_id, goal, context_json, state, started_at, completed_at)
            ON t.execution_id = s.execution_id
            WHEN MATCHED THEN UPDATE SET
                trace_id = s.trace_id, goal = s.goal, context_json = s.context_json, state = s.state,
                started_at = s.started_at, completed_at = s.completed_at
            WHEN NOT MATCHED THEN INSERT
                (execution_id, trace_id, goal, context_json, state, started_at, completed_at)
                VALUES (s.execution_id, s.trace_id, s.goal, s.context_json, s.state, s.started_at, s.completed_at)
        """;
        try (Connection conn = dataSource.getConnection()) {
            String contextJson = JSON.writeValueAsString(record.context());
            executeMerge(conn, sql, ps -> {
                ps.setString(1, record.executionId());
                ps.setString(2, record.traceId());
                ps.setString(3, record.goal());
                ps.setString(4, contextJson);
                ps.setString(5, record.state().name());
                ps.setTimestamp(6, Timestamp.from(record.startedAt()));
                ps.setTimestamp(7, record.completedAt() != null ? Timestamp.from(record.completedAt()) : null);
            });
        } catch (Exception e) {
            log.error("Failed to create execution record executionId={}", record.executionId(), e);
        }
    }

    @Override
    public void updateExecution(String executionId, ExecutionState state, Instant completedAt) {
        String sql = "UPDATE nexora_executions SET state = ?, completed_at = ? WHERE execution_id = ?";
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, state.name());
            ps.setTimestamp(2, completedAt != null ? Timestamp.from(completedAt) : null);
            ps.setString(3, executionId);
            ps.executeUpdate();
        } catch (SQLException e) {
            // Thrown, not swallowed: the engine retries state writes, and a silently lost
            // terminal state (e.g. stuck at COMPENSATING) would block replay forever.
            throw new IllegalStateException("Failed to update execution state executionId=" + executionId, e);
        }
    }

    @Override
    public void upsertStep(String executionId, StepRecord step) {
        // Completion and failure updates carry no idempotency key. COALESCE keeps the key recorded at
        // step start, which replay needs to avoid repeating side effects. The output is overwritten,
        // not kept: a restarted step must not show the output of its earlier run.
        String sql = """
            MERGE INTO nexora_steps AS t
            USING (VALUES (CAST(? AS VARCHAR(36)), CAST(? AS VARCHAR(200)), CAST(? AS VARCHAR(200)),
                           CAST(? AS VARCHAR(36)), CAST(? AS VARCHAR(30)), CAST(? AS VARCHAR(100)), CAST(? AS TEXT),
                           CAST(? AS TIMESTAMP), CAST(? AS TIMESTAMP), CAST(? AS BIGINT), CAST(? AS TEXT),
                           CAST(? AS BOOLEAN)))
                AS s (execution_id, step_id, capability_id, idempotency_key, state,
                      failure_code, failure_message, started_at, completed_at, duration_ms,
                      output_json, output_truncated)
            ON t.execution_id = s.execution_id AND t.step_id = s.step_id
            WHEN MATCHED THEN UPDATE SET
                capability_id = s.capability_id,
                idempotency_key = COALESCE(s.idempotency_key, t.idempotency_key),
                state = s.state, failure_code = s.failure_code, failure_message = s.failure_message,
                started_at = s.started_at, completed_at = s.completed_at, duration_ms = s.duration_ms,
                output_json = s.output_json, output_truncated = s.output_truncated
            WHEN NOT MATCHED THEN INSERT
                (execution_id, step_id, capability_id, idempotency_key, state,
                 failure_code, failure_message, started_at, completed_at, duration_ms,
                 output_json, output_truncated)
                VALUES (s.execution_id, s.step_id, s.capability_id, s.idempotency_key, s.state,
                        s.failure_code, s.failure_message, s.started_at, s.completed_at, s.duration_ms,
                        s.output_json, s.output_truncated)
        """;
        try (Connection conn = dataSource.getConnection()) {
            String outputJson = outputJson(executionId, step);
            executeMerge(conn, sql, ps -> {
                ps.setString(1, executionId);
                ps.setString(2, step.stepId());
                ps.setString(3, step.capabilityId());
                ps.setString(4, step.idempotencyKey());
                ps.setString(5, step.state().name());
                ps.setString(6, step.failureCode());
                ps.setString(7, step.failureMessage());
                ps.setTimestamp(8, step.startedAt() != null ? Timestamp.from(step.startedAt()) : null);
                ps.setTimestamp(9, step.completedAt() != null ? Timestamp.from(step.completedAt()) : null);
                ps.setLong(10, step.durationMs());
                ps.setString(11, outputJson);
                ps.setBoolean(12, step.outputTruncated());
            });
        } catch (SQLException e) {
            log.error("Failed to upsert step executionId={} stepId={}", executionId, step.stepId(), e);
        }
    }

    /**
     * Runs an upsert MERGE, retrying when it loses an insert race. Two concurrent MERGEs on a key
     * that does not exist yet can both take WHEN NOT MATCHED, and the later INSERT then fails on the
     * primary key (H2 and PostgreSQL alike). The retry finds the row and takes WHEN MATCHED instead.
     */
    private static void executeMerge(Connection conn, String sql, StatementBinder binder) throws SQLException {
        for (int attempt = 1; ; attempt++) {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                binder.bind(ps);
                ps.executeUpdate();
                return;
            } catch (SQLException e) {
                if (!UNIQUE_VIOLATION.equals(e.getSQLState()) || attempt == MERGE_ATTEMPTS) {
                    throw e;
                }
                log.debug("Upsert lost an insert race, retrying as an update attempt={}", attempt);
            }
        }
    }

    @FunctionalInterface
    private interface StatementBinder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    @Override
    public void recordWebhookDelivery(com.nexora.persistence.WebhookDeliveryRecord record) {
        if (record.deliveryId() == null
                || record.executionId() == null
                || record.url() == null
                || record.timestamp() == null) {
            throw new IllegalArgumentException(
                    "Required webhook delivery fields must not be null: deliveryId, executionId, url, timestamp");
        }
        String sql = """
            INSERT INTO nexora_webhook_deliveries
                (delivery_id, execution_id, url, status_code, attempt, successful, timestamp, error_message)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """;
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, record.deliveryId());
            ps.setString(2, record.executionId());
            ps.setString(3, record.url());
            ps.setInt(4, record.statusCode());
            ps.setInt(5, record.attempt());
            ps.setBoolean(6, record.successful());
            ps.setTimestamp(7, Timestamp.from(record.timestamp()));
            ps.setString(8, record.errorMessage());
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to insert webhook delivery executionId={}", record.executionId(), e);
        }
    }

    @Override
    public List<com.nexora.persistence.WebhookDeliveryRecord> getWebhookDeliveries(String executionId) {
        String sql = "SELECT * FROM nexora_webhook_deliveries WHERE execution_id = ? ORDER BY timestamp ASC";
        List<com.nexora.persistence.WebhookDeliveryRecord> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, executionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(new com.nexora.persistence.WebhookDeliveryRecord(
                            rs.getString("delivery_id"),
                            rs.getString("execution_id"),
                            rs.getString("url"),
                            rs.getInt("status_code"),
                            rs.getInt("attempt"),
                            rs.getBoolean("successful"),
                            rs.getTimestamp("timestamp").toInstant(),
                            rs.getString("error_message")));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to query webhook deliveries executionId={}", executionId, e);
        }
        return results;
    }

    @Override
    public Optional<ExecutionRecord> findById(String executionId) {
        String sql = "SELECT * FROM nexora_executions WHERE execution_id = ?";
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, executionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                ExecutionRecord exec = mapExecution(rs);
                return Optional.of(withSteps(conn, exec));
            }
        } catch (Exception e) {
            // Never report a failed lookup as "not found": callers would answer 404 for an outage.
            throw new IllegalStateException("Failed to find execution executionId=" + executionId, e);
        }
    }

    @Override
    public List<ExecutionRecord> findRecent(int limit) {
        String sql = "SELECT * FROM nexora_executions ORDER BY started_at DESC LIMIT ?";
        List<ExecutionRecord> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(withSteps(conn, mapExecution(rs)));
                }
            }
        } catch (Exception e) {
            log.error("Failed to query recent executions", e);
        }
        return results;
    }

    @Override
    public void createDeadLetter(DeadLetterRecord record) {
        String sql = """
            INSERT INTO nexora_dead_letters
                (id, execution_id, goal, context_json, failure_code, failure_message, failed_at, review_state, resolve_reason)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, record.id());
            ps.setString(2, record.executionId());
            ps.setString(3, record.goal());
            ps.setString(4, JSON.writeValueAsString(record.context()));
            ps.setString(5, record.failureCode());
            ps.setString(6, record.failureMessage());
            ps.setTimestamp(7, Timestamp.from(record.failedAt()));
            ps.setString(8, record.reviewState().name());
            ps.setString(9, record.resolveReason());
            ps.executeUpdate();
        } catch (Exception e) {
            log.error("Failed to create dead letter id={} executionId={}", record.id(), record.executionId(), e);
        }
    }

    @Override
    public Optional<DeadLetterRecord> findDeadLetterById(String id) {
        String sql = "SELECT * FROM nexora_dead_letters WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(mapDeadLetter(rs));
            }
        } catch (Exception e) {
            log.error("Failed to find dead letter id={}", id, e);
            return Optional.empty();
        }
    }

    @Override
    public List<DeadLetterRecord> findDeadLetters(DeadLetterReviewState state, int offset, int limit) {
        String sql = state != null
                ? "SELECT * FROM nexora_dead_letters WHERE review_state = ? ORDER BY failed_at DESC LIMIT ? OFFSET ?"
                : "SELECT * FROM nexora_dead_letters ORDER BY failed_at DESC LIMIT ? OFFSET ?";
        List<DeadLetterRecord> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            if (state != null) {
                ps.setString(1, state.name());
                ps.setInt(2, limit);
                ps.setInt(3, offset);
            } else {
                ps.setInt(1, limit);
                ps.setInt(2, offset);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(mapDeadLetter(rs));
                }
            }
        } catch (Exception e) {
            log.error("Failed to query dead letters state={}", state, e);
        }
        return results;
    }

    @Override
    public void updateDeadLetterState(String id, DeadLetterReviewState state, String resolveReason) {
        String sql = "UPDATE nexora_dead_letters SET review_state = ?, resolve_reason = ? WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, state.name());
            ps.setString(2, resolveReason);
            ps.setString(3, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to update dead letter state id={}", id, e);
        }
    }

    private DeadLetterRecord mapDeadLetter(ResultSet rs) throws Exception {
        Map<String, Object> ctx = rs.getString("context_json") != null
                ? JSON.readValue(rs.getString("context_json"), MAP_TYPE)
                : Map.of();
        String rawState = rs.getString("review_state");
        DeadLetterReviewState reviewState;
        try {
            reviewState = rawState != null ? DeadLetterReviewState.valueOf(rawState) : DeadLetterReviewState.PENDING;
        } catch (IllegalArgumentException e) {
            log.warn(
                    "Unknown review_state '{}' in dead letter id={}, defaulting to PENDING",
                    rawState,
                    rs.getString("id"));
            reviewState = DeadLetterReviewState.PENDING;
        }
        return new DeadLetterRecord(
                rs.getString("id"),
                rs.getString("execution_id"),
                rs.getString("goal"),
                ctx,
                rs.getString("failure_code"),
                rs.getString("failure_message"),
                rs.getTimestamp("failed_at").toInstant(),
                reviewState,
                rs.getString("resolve_reason"));
    }

    @Override
    public void createSchedule(ScheduleRecord record) {
        String sql = """
            INSERT INTO nexora_schedules
                (id, cron_expression, goal, context_json, missed_fire_policy,
                 created_at, last_fired_at, next_fire_at, last_status, active)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, record.id());
            ps.setString(2, record.cronExpression());
            ps.setString(3, record.goal());
            ps.setString(4, JSON.writeValueAsString(record.context()));
            ps.setString(5, record.missedFirePolicy().name());
            ps.setTimestamp(6, Timestamp.from(record.createdAt()));
            ps.setTimestamp(7, record.lastFiredAt() != null ? Timestamp.from(record.lastFiredAt()) : null);
            ps.setTimestamp(8, Timestamp.from(record.nextFireAt()));
            ps.setString(9, record.lastStatus().name());
            ps.setBoolean(10, record.active());
            ps.executeUpdate();
        } catch (Exception e) {
            log.error("Failed to create schedule id={}", record.id(), e);
            throw new IllegalStateException("Failed to create schedule id=" + record.id(), e);
        }
    }

    @Override
    public Optional<ScheduleRecord> findScheduleById(String id) {
        String sql = "SELECT * FROM nexora_schedules WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(mapSchedule(rs));
            }
        } catch (Exception e) {
            log.error("Failed to find schedule id={}", id, e);
            throw new IllegalStateException("Failed to find schedule id=" + id, e);
        }
    }

    @Override
    public List<ScheduleRecord> findActiveSchedules() {
        String sql = "SELECT * FROM nexora_schedules WHERE active = TRUE ORDER BY next_fire_at ASC";
        List<ScheduleRecord> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                try {
                    results.add(mapSchedule(rs));
                } catch (Exception e) {
                    log.warn("Skipping malformed schedule row id={}: {}", rs.getString("id"), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("Failed to query active schedules", e);
            throw new IllegalStateException("Failed to query active schedules", e);
        }
        return results;
    }

    @Override
    public List<ScheduleRecord> findAllSchedules() {
        String sql = "SELECT * FROM nexora_schedules ORDER BY created_at DESC";
        List<ScheduleRecord> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                try {
                    results.add(mapSchedule(rs));
                } catch (Exception e) {
                    log.warn("Skipping malformed schedule row id={}: {}", rs.getString("id"), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("Failed to query schedules", e);
            throw new IllegalStateException("Failed to query schedules", e);
        }
        return results;
    }

    @Override
    public void updateScheduleLastFired(String id, Instant lastFiredAt, Instant nextFireAt) {
        String sql = "UPDATE nexora_schedules SET last_fired_at = ?, next_fire_at = ? WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.from(lastFiredAt));
            ps.setTimestamp(2, Timestamp.from(nextFireAt));
            ps.setString(3, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to update schedule last_fired_at id={}", id, e);
            throw new IllegalStateException("Failed to update schedule last_fired_at id=" + id, e);
        }
    }

    @Override
    public void updateScheduleNextFire(String id, Instant nextFireAt) {
        String sql = "UPDATE nexora_schedules SET next_fire_at = ? WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.from(nextFireAt));
            ps.setString(2, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to update schedule next_fire_at id={}", id, e);
            throw new IllegalStateException("Failed to update schedule next_fire_at id=" + id, e);
        }
    }

    @Override
    public void deactivateSchedule(String id) {
        String sql = "UPDATE nexora_schedules SET active = FALSE, last_status = 'CANCELED' WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to deactivate schedule id={}", id, e);
            throw new IllegalStateException("Failed to deactivate schedule id=" + id, e);
        }
    }

    @Override
    public void updateScheduleStatus(String id, ScheduleStatus status) {
        String sql = "UPDATE nexora_schedules SET last_status = ? WHERE id = ? AND active = TRUE";
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status.name());
            ps.setString(2, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to update schedule status id={}", id, e);
            throw new IllegalStateException("Failed to update schedule status id=" + id, e);
        }
    }

    private ScheduleRecord mapSchedule(ResultSet rs) throws Exception {
        Map<String, Object> ctx = rs.getString("context_json") != null
                ? JSON.readValue(rs.getString("context_json"), MAP_TYPE)
                : Map.of();
        Timestamp lastFiredAt = rs.getTimestamp("last_fired_at");
        return new ScheduleRecord(
                rs.getString("id"),
                rs.getString("cron_expression"),
                rs.getString("goal"),
                ctx,
                parseMissedFirePolicy(rs.getString("missed_fire_policy")),
                rs.getTimestamp("created_at").toInstant(),
                lastFiredAt != null ? lastFiredAt.toInstant() : null,
                rs.getTimestamp("next_fire_at").toInstant(),
                parseScheduleStatus(rs.getString("last_status")),
                rs.getBoolean("active"));
    }

    private static MissedFirePolicy parseMissedFirePolicy(String value) {
        try {
            return MissedFirePolicy.valueOf(value);
        } catch (IllegalArgumentException e) {
            log.warn("Unknown missed_fire_policy '{}', defaulting to FIRE_ONCE", value);
            return MissedFirePolicy.FIRE_ONCE;
        }
    }

    private static ScheduleStatus parseScheduleStatus(String value) {
        try {
            return ScheduleStatus.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException e) {
            log.warn("Unknown last_status '{}', defaulting to NEVER_RUN", value);
            return ScheduleStatus.NEVER_RUN;
        }
    }

    public static JdbcExecutionStore h2AutoServer(String filePath) {
        return h2AutoServer(filePath, PoolConfig.singleConnection());
    }

    /** File-backed H2 that other processes can open concurrently, behind a pool sized by {@code poolConfig}. */
    public static JdbcExecutionStore h2AutoServer(String filePath, PoolConfig poolConfig) {
        return new JdbcExecutionStore("jdbc:h2:" + filePath + ";AUTO_SERVER=TRUE", poolConfig);
    }

    @Override
    public void close() {
        if (ownedPool != null) {
            ownedPool.close();
        }
    }

    /**
     * Borrows a connection and validates it. When every pooled connection is busy this waits up to
     * the pool's connection timeout, then reports DOWN: a saturated pool cannot take new work.
     */
    @Override
    public boolean isHealthy() {
        try (Connection conn = dataSource.getConnection()) {
            return conn.isValid(HEALTH_CHECK_TIMEOUT_SECONDS);
        } catch (SQLException e) {
            log.warn("Persistence health check failed sqlState={}", e.getSQLState(), e);
            return false;
        }
    }

    @Override
    public boolean isSchemaCurrent() {
        try (Connection conn = dataSource.getConnection()) {
            return schema.isCurrent(conn);
        } catch (SQLException e) {
            log.warn("Persistence schema check failed sqlState={}", e.getSQLState(), e);
            return false;
        }
    }

    private static final String HISTORY_COLUMNS =
            "execution_id, trace_id, goal, context_json, state, started_at, completed_at";

    @Override
    public List<ExecutionRecord> findByFilter(ExecutionFilter filter) {
        HistoryQuery where = HistoryQuery.from(filter, true);
        String sql = "SELECT " + HISTORY_COLUMNS + " FROM nexora_executions" + where.clause()
                + " ORDER BY started_at DESC, execution_id DESC LIMIT ?";
        List<ExecutionRecord> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            int next = where.bind(ps);
            ps.setInt(next, filter.limit());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(mapExecution(rs));
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to query execution history", e);
        }
        return results;
    }

    @Override
    public long count(ExecutionFilter filter) {
        HistoryQuery where = HistoryQuery.from(filter, false);
        String sql = "SELECT COUNT(*) FROM nexora_executions" + where.clause();
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            where.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to count execution history", e);
        }
    }

    /**
     * WHERE clause for the history queries. The SQL text is assembled only from the constant
     * fragments below; every user-supplied value is a bound parameter.
     */
    private record HistoryQuery(List<String> conditions, List<Object> params) {

        private static final char LIKE_ESCAPE = '\\';

        static HistoryQuery from(ExecutionFilter filter, boolean includeCursor) {
            List<String> conditions = new ArrayList<>();
            List<Object> params = new ArrayList<>();
            if (!filter.states().isEmpty()) {
                conditions.add("state IN ("
                        + String.join(", ", Collections.nCopies(filter.states().size(), "?")) + ")");
                filter.states().stream().map(Enum::name).sorted().forEach(params::add);
            }
            if (filter.goalContains() != null) {
                conditions.add("LOWER(goal) LIKE ? ESCAPE '\\'");
                params.add("%" + escapeLike(filter.goalContains().toLowerCase(Locale.ROOT)) + "%");
            }
            if (filter.from() != null) {
                conditions.add("started_at >= ?");
                params.add(Timestamp.from(filter.from()));
            }
            if (filter.to() != null) {
                conditions.add("started_at < ?");
                params.add(Timestamp.from(filter.to()));
            }
            ExecutionCursor after = filter.after();
            if (includeCursor && after != null) {
                Timestamp cursorTime = Timestamp.from(after.startedAt());
                conditions.add("(started_at < ? OR (started_at = ? AND execution_id < ?))");
                params.add(cursorTime);
                params.add(cursorTime);
                params.add(after.executionId());
            }
            return new HistoryQuery(conditions, params);
        }

        String clause() {
            return conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
        }

        /** Binds all parameters and returns the next free parameter index. */
        int bind(PreparedStatement ps) throws SQLException {
            int index = 1;
            for (Object param : params) {
                ps.setObject(index++, param);
            }
            return index;
        }

        private static String escapeLike(String value) {
            StringBuilder escaped = new StringBuilder(value.length());
            for (char c : value.toCharArray()) {
                if (c == LIKE_ESCAPE || c == '%' || c == '_') {
                    escaped.append(LIKE_ESCAPE);
                }
                escaped.append(c);
            }
            return escaped.toString();
        }
    }

    private ExecutionRecord mapExecution(ResultSet rs) throws Exception {
        Timestamp completedAt = rs.getTimestamp("completed_at");
        Map<String, Object> ctx = JSON.readValue(rs.getString("context_json"), MAP_TYPE);
        return new ExecutionRecord(
                rs.getString("execution_id"),
                rs.getString("trace_id"),
                rs.getString("goal"),
                ctx,
                ExecutionState.valueOf(rs.getString("state")),
                rs.getTimestamp("started_at").toInstant(),
                completedAt != null ? completedAt.toInstant() : null,
                List.of());
    }

    /**
     * The output column for a step, or null when it has none. An output that cannot be written as
     * JSON (a record built by hand rather than through StepOutput) is dropped, not the whole step.
     */
    private static String outputJson(String executionId, StepRecord step) {
        if (step.output().isEmpty()) {
            return null;
        }
        try {
            return JSON.writeValueAsString(step.output());
        } catch (JsonProcessingException e) {
            log.warn(
                    "Step output not stored executionId={} stepId={} error={}",
                    executionId,
                    step.stepId(),
                    e.getOriginalMessage());
            return null;
        }
    }

    /**
     * The step's recorded output. A row whose JSON does not parse (written by hand, or damaged)
     * reads as no output instead of making the whole execution unreadable.
     */
    private static Map<String, Object> readOutput(ResultSet rs, String executionId) throws SQLException {
        String json = rs.getString("output_json");
        if (json == null) {
            return Map.of();
        }
        try {
            return JSON.readValue(json, MAP_TYPE);
        } catch (JsonProcessingException e) {
            log.warn(
                    "Unreadable step output ignored executionId={} stepId={} error={}",
                    executionId,
                    rs.getString("step_id"),
                    e.getOriginalMessage());
            return Map.of();
        }
    }

    /** Runs on the caller's connection so a lookup never needs a second one from the pool. */
    private static ExecutionRecord withSteps(Connection conn, ExecutionRecord exec) throws SQLException {
        String sql = "SELECT * FROM nexora_steps WHERE execution_id = ? ORDER BY started_at";
        List<StepRecord> steps = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, exec.executionId());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Timestamp startedAt = rs.getTimestamp("started_at");
                    Timestamp completedAt = rs.getTimestamp("completed_at");
                    steps.add(new StepRecord(
                            rs.getString("step_id"),
                            rs.getString("capability_id"),
                            rs.getString("idempotency_key"),
                            StepState.valueOf(rs.getString("state")),
                            rs.getString("failure_code"),
                            rs.getString("failure_message"),
                            startedAt != null ? startedAt.toInstant() : null,
                            completedAt != null ? completedAt.toInstant() : null,
                            rs.getLong("duration_ms"),
                            readOutput(rs, exec.executionId()),
                            rs.getBoolean("output_truncated")));
                }
            }
        }
        return new ExecutionRecord(
                exec.executionId(),
                exec.traceId(),
                exec.goal(),
                exec.context(),
                exec.state(),
                exec.startedAt(),
                exec.completedAt(),
                steps);
    }
}
