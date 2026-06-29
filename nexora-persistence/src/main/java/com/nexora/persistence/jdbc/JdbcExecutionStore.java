package com.nexora.persistence.jdbc;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexora.persistence.DeadLetterRecord;
import com.nexora.persistence.DeadLetterReviewState;
import com.nexora.persistence.ExecutionRecord;
import com.nexora.persistence.ExecutionState;
import com.nexora.persistence.ExecutionStore;
import com.nexora.persistence.MissedFirePolicy;
import com.nexora.persistence.ScheduleRecord;
import com.nexora.persistence.StepRecord;
import com.nexora.persistence.StepState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * JDBC-backed ExecutionStore. Ships with H2 as the default embedded database.
 * For production, supply a DataSource URL pointing at PostgreSQL, MySQL, etc.
 *
 * Schema is created automatically on first use (DDL is idempotent).
 *
 * Connection management: a single dedicated connection protected by synchronized blocks.
 * This is intentional for embedded H2; replace with a DataSource/HikariCP when using
 * a remote database.
 */
public final class JdbcExecutionStore implements ExecutionStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcExecutionStore.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final Connection conn;

    public JdbcExecutionStore(String jdbcUrl) {
        try {
            this.conn = DriverManager.getConnection(jdbcUrl);
            initSchema();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to connect to persistence store: " + jdbcUrl, e);
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

    private void initSchema() throws SQLException {
        try (var stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS nexora_executions (
                    execution_id  VARCHAR(36)  PRIMARY KEY,
                    trace_id      VARCHAR(64),
                    goal          TEXT,
                    context_json  TEXT,
                    state         VARCHAR(30)  NOT NULL,
                    started_at    TIMESTAMP    NOT NULL,
                    completed_at  TIMESTAMP
                )
            """);
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS nexora_steps (
                    execution_id     VARCHAR(36)  NOT NULL,
                    step_id          VARCHAR(200) NOT NULL,
                    capability_id    VARCHAR(200) NOT NULL,
                    idempotency_key  VARCHAR(36),
                    state            VARCHAR(30)  NOT NULL,
                    failure_code     VARCHAR(100),
                    failure_message  TEXT,
                    started_at       TIMESTAMP,
                    completed_at     TIMESTAMP,
                    duration_ms      BIGINT,
                    PRIMARY KEY (execution_id, step_id),
                    FOREIGN KEY (execution_id) REFERENCES nexora_executions(execution_id)
                )
            """);
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS nexora_webhook_deliveries (
                    delivery_id      VARCHAR(36)  PRIMARY KEY,
                    execution_id     VARCHAR(36)  NOT NULL,
                    url              TEXT         NOT NULL,
                    status_code      INT          NOT NULL,
                    attempt          INT          NOT NULL,
                    successful       BOOLEAN      NOT NULL,
                    timestamp        TIMESTAMP    NOT NULL,
                    error_message    TEXT,
                    FOREIGN KEY (execution_id) REFERENCES nexora_executions(execution_id)
                )
            """);
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS nexora_dead_letters (
                    id               VARCHAR(36)  PRIMARY KEY,
                    execution_id     VARCHAR(36)  NOT NULL,
                    goal             TEXT,
                    context_json     TEXT,
                    failure_code     VARCHAR(100),
                    failure_message  TEXT,
                    failed_at        TIMESTAMP    NOT NULL,
                    review_state     VARCHAR(30)  NOT NULL DEFAULT 'PENDING',
                    resolve_reason   TEXT
                )
            """);
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS nexora_schedules (
                    id                   VARCHAR(36)   PRIMARY KEY,
                    cron_expression      VARCHAR(120)  NOT NULL,
                    goal                 TEXT          NOT NULL,
                    context_json         TEXT,
                    missed_fire_policy   VARCHAR(20)   NOT NULL DEFAULT 'FIRE_ONCE',
                    created_at           TIMESTAMP     NOT NULL,
                    last_fired_at        TIMESTAMP,
                    next_fire_at         TIMESTAMP     NOT NULL,
                    active               BOOLEAN       NOT NULL DEFAULT TRUE
                )
            """);
        }
    }

    @Override
    public synchronized void createExecution(ExecutionRecord record) {
        String sql = """
            MERGE INTO nexora_executions
                (execution_id, trace_id, goal, context_json, state, started_at, completed_at)
            KEY (execution_id)
            VALUES (?, ?, ?, ?, ?, ?, ?)
        """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, record.executionId());
            ps.setString(2, record.traceId());
            ps.setString(3, record.goal());
            ps.setString(4, JSON.writeValueAsString(record.context()));
            ps.setString(5, record.state().name());
            ps.setTimestamp(6, Timestamp.from(record.startedAt()));
            ps.setTimestamp(7, record.completedAt() != null ? Timestamp.from(record.completedAt()) : null);
            ps.executeUpdate();
        } catch (Exception e) {
            log.error("Failed to create execution record executionId={}", record.executionId(), e);
        }
    }

    @Override
    public synchronized void updateExecution(String executionId, ExecutionState state, Instant completedAt) {
        String sql = "UPDATE nexora_executions SET state = ?, completed_at = ? WHERE execution_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, state.name());
            ps.setTimestamp(2, completedAt != null ? Timestamp.from(completedAt) : null);
            ps.setString(3, executionId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to update execution state executionId={}", executionId, e);
        }
    }

    @Override
    public synchronized void upsertStep(String executionId, StepRecord step) {
        String sql = """
            MERGE INTO nexora_steps
                (execution_id, step_id, capability_id, idempotency_key, state,
                 failure_code, failure_message, started_at, completed_at, duration_ms)
            KEY (execution_id, step_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
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
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to upsert step executionId={} stepId={}", executionId, step.stepId(), e);
        }
    }

    @Override
    public synchronized void recordWebhookDelivery(com.nexora.persistence.WebhookDeliveryRecord record) {
        if (record.deliveryId() == null || record.executionId() == null ||
            record.url() == null || record.timestamp() == null) {
            throw new IllegalArgumentException(
                "Required webhook delivery fields must not be null: deliveryId, executionId, url, timestamp");
        }
        String sql = """
            INSERT INTO nexora_webhook_deliveries
                (delivery_id, execution_id, url, status_code, attempt, successful, timestamp, error_message)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
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
    public synchronized List<com.nexora.persistence.WebhookDeliveryRecord> getWebhookDeliveries(String executionId) {
        String sql = "SELECT * FROM nexora_webhook_deliveries WHERE execution_id = ? ORDER BY timestamp ASC";
        List<com.nexora.persistence.WebhookDeliveryRecord> results = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
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
                            rs.getString("error_message")
                    ));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to query webhook deliveries executionId={}", executionId, e);
        }
        return results;
    }

    @Override
    public synchronized Optional<ExecutionRecord> findById(String executionId) {
        String sql = "SELECT * FROM nexora_executions WHERE execution_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, executionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                ExecutionRecord exec = mapExecution(rs);
                return Optional.of(withSteps(exec));
            }
        } catch (Exception e) {
            log.error("Failed to find execution executionId={}", executionId, e);
            return Optional.empty();
        }
    }

    @Override
    public synchronized List<ExecutionRecord> findRecent(int limit) {
        String sql = "SELECT * FROM nexora_executions ORDER BY started_at DESC LIMIT ?";
        List<ExecutionRecord> results = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(withSteps(mapExecution(rs)));
                }
            }
        } catch (Exception e) {
            log.error("Failed to query recent executions", e);
        }
        return results;
    }

    @Override
    public synchronized void createDeadLetter(DeadLetterRecord record) {
        String sql = """
            INSERT INTO nexora_dead_letters
                (id, execution_id, goal, context_json, failure_code, failure_message, failed_at, review_state, resolve_reason)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
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
    public synchronized Optional<DeadLetterRecord> findDeadLetterById(String id) {
        String sql = "SELECT * FROM nexora_dead_letters WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
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
    public synchronized List<DeadLetterRecord> findDeadLetters(DeadLetterReviewState state, int offset, int limit) {
        String sql = state != null
                ? "SELECT * FROM nexora_dead_letters WHERE review_state = ? ORDER BY failed_at DESC LIMIT ? OFFSET ?"
                : "SELECT * FROM nexora_dead_letters ORDER BY failed_at DESC LIMIT ? OFFSET ?";
        List<DeadLetterRecord> results = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
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
    public synchronized void updateDeadLetterState(String id, DeadLetterReviewState state, String resolveReason) {
        String sql = "UPDATE nexora_dead_letters SET review_state = ?, resolve_reason = ? WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
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
            log.warn("Unknown review_state '{}' in dead letter id={}, defaulting to PENDING", rawState, rs.getString("id"));
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
                rs.getString("resolve_reason")
        );
    }

    @Override
    public synchronized void createSchedule(ScheduleRecord record) {
        String sql = """
            INSERT INTO nexora_schedules
                (id, cron_expression, goal, context_json, missed_fire_policy,
                 created_at, last_fired_at, next_fire_at, active)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, record.id());
            ps.setString(2, record.cronExpression());
            ps.setString(3, record.goal());
            ps.setString(4, JSON.writeValueAsString(record.context()));
            ps.setString(5, record.missedFirePolicy().name());
            ps.setTimestamp(6, Timestamp.from(record.createdAt()));
            ps.setTimestamp(7, record.lastFiredAt() != null ? Timestamp.from(record.lastFiredAt()) : null);
            ps.setTimestamp(8, Timestamp.from(record.nextFireAt()));
            ps.setBoolean(9, record.active());
            ps.executeUpdate();
        } catch (Exception e) {
            log.error("Failed to create schedule id={}", record.id(), e);
        }
    }

    @Override
    public synchronized Optional<ScheduleRecord> findScheduleById(String id) {
        String sql = "SELECT * FROM nexora_schedules WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(mapSchedule(rs));
            }
        } catch (Exception e) {
            log.error("Failed to find schedule id={}", id, e);
            return Optional.empty();
        }
    }

    @Override
    public synchronized List<ScheduleRecord> findActiveSchedules() {
        String sql = "SELECT * FROM nexora_schedules WHERE active = TRUE ORDER BY next_fire_at ASC";
        List<ScheduleRecord> results = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) results.add(mapSchedule(rs));
        } catch (Exception e) {
            log.error("Failed to query active schedules", e);
        }
        return results;
    }

    @Override
    public synchronized List<ScheduleRecord> findAllSchedules() {
        String sql = "SELECT * FROM nexora_schedules ORDER BY created_at DESC";
        List<ScheduleRecord> results = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) results.add(mapSchedule(rs));
        } catch (Exception e) {
            log.error("Failed to query schedules", e);
        }
        return results;
    }

    @Override
    public synchronized void updateScheduleLastFired(String id, Instant lastFiredAt, Instant nextFireAt) {
        String sql = "UPDATE nexora_schedules SET last_fired_at = ?, next_fire_at = ? WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.from(lastFiredAt));
            ps.setTimestamp(2, Timestamp.from(nextFireAt));
            ps.setString(3, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to update schedule last_fired_at id={}", id, e);
        }
    }

    @Override
    public synchronized void deactivateSchedule(String id) {
        String sql = "UPDATE nexora_schedules SET active = FALSE WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to deactivate schedule id={}", id, e);
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
                MissedFirePolicy.valueOf(rs.getString("missed_fire_policy")),
                rs.getTimestamp("created_at").toInstant(),
                lastFiredAt != null ? lastFiredAt.toInstant() : null,
                rs.getTimestamp("next_fire_at").toInstant(),
                rs.getBoolean("active")
        );
    }

    @Override
    public synchronized void close() {
        try { conn.close(); } catch (SQLException ignored) {}
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
                List.of()
        );
    }

    private ExecutionRecord withSteps(ExecutionRecord exec) throws SQLException {
        String sql = "SELECT * FROM nexora_steps WHERE execution_id = ? ORDER BY started_at";
        List<StepRecord> steps = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, exec.executionId());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Timestamp startedAt  = rs.getTimestamp("started_at");
                    Timestamp completedAt = rs.getTimestamp("completed_at");
                    steps.add(new StepRecord(
                            rs.getString("step_id"),
                            rs.getString("capability_id"),
                            rs.getString("idempotency_key"),
                            StepState.valueOf(rs.getString("state")),
                            rs.getString("failure_code"),
                            rs.getString("failure_message"),
                            startedAt  != null ? startedAt.toInstant()  : null,
                            completedAt != null ? completedAt.toInstant() : null,
                            rs.getLong("duration_ms")
                    ));
                }
            }
        }
        return new ExecutionRecord(exec.executionId(), exec.traceId(), exec.goal(), exec.context(),
                exec.state(), exec.startedAt(), exec.completedAt(), steps);
    }
}
