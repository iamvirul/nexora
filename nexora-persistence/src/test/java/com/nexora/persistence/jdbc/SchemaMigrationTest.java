package com.nexora.persistence.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nexora.core.tenant.TenantId;
import com.nexora.persistence.ExecutionRecord;
import com.nexora.persistence.ExecutionState;
import com.nexora.persistence.MissedFirePolicy;
import com.nexora.persistence.ScheduleRecord;
import com.nexora.persistence.ScheduleStatus;
import com.nexora.persistence.StepRecord;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SchemaMigrationTest {

    private static final Instant T0 = Instant.parse("2026-10-01T10:00:00Z");
    private static final String BROKEN_LOCATION = "classpath:db/nexora-test/broken";
    private static final String EDITED_LOCATION = "classpath:db/nexora-test/edited";
    private static final String V1_ONLY_LOCATION = "classpath:db/nexora-test/v1-only";
    private static final String THROUGH_V2_LOCATION = "classpath:db/nexora-test/through-v2";

    private JdbcDataSource database;

    @BeforeEach
    void setUp() {
        database = new JdbcDataSource();
        database.setURL("jdbc:h2:mem:migration_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
    }

    @Test
    void freshDatabaseIsMigratedToTheLatestVersionOnConstruction() throws SQLException {
        JdbcExecutionStore store = new JdbcExecutionStore(database);

        assertEquals(List.of("1:true", "2:true", "3:true"), history());
        assertTrue(store.isSchemaCurrent());
    }

    @Test
    void constructingTwiceOnTheSameDatabaseAppliesNothingNew() throws SQLException {
        new JdbcExecutionStore(database);
        JdbcExecutionStore second = new JdbcExecutionStore(database);

        assertEquals(List.of("1:true", "2:true", "3:true"), history());
        assertTrue(second.isSchemaCurrent());
    }

    @Test
    void failingMigrationAbortsConstructionNamingTheScript() {
        IllegalStateException error =
                assertThrows(IllegalStateException.class, () -> new JdbcExecutionStore(database, BROKEN_LOCATION));

        assertTrue(error.getMessage().startsWith("Persistence schema migration failed"), error.getMessage());
        assertTrue(error.getMessage().contains("V1__broken.sql"), error.getMessage());
    }

    @Test
    void editedAppliedMigrationAbortsConstruction() {
        new JdbcExecutionStore(database);

        IllegalStateException error =
                assertThrows(IllegalStateException.class, () -> new JdbcExecutionStore(database, EDITED_LOCATION));

        assertTrue(error.getMessage().contains("checksum mismatch"), error.getMessage());
    }

    @Test
    void missingMigrationLocationAbortsConstruction() {
        assertThrows(
                IllegalStateException.class,
                () -> new JdbcExecutionStore(database, "classpath:db/nexora-test/does-not-exist"));
    }

    @Test
    void locationWithoutMigrationsAbortsConstruction() {
        IllegalStateException error = assertThrows(
                IllegalStateException.class, () -> new JdbcExecutionStore(database, "classpath:db/nexora-test/empty"));

        assertTrue(error.getMessage().contains("No persistence schema migrations found"), error.getMessage());
    }

    @Test
    void preFlywayStoreIsAdoptedKeepingItsDataAndGainingMissingTables() throws SQLException {
        // An early release's store: executions only, no schedules table, no Flyway history.
        execute("CREATE TABLE nexora_executions (execution_id VARCHAR(36) PRIMARY KEY, trace_id VARCHAR(64),"
                + " goal TEXT, context_json TEXT, state VARCHAR(30) NOT NULL, started_at TIMESTAMP NOT NULL,"
                + " completed_at TIMESTAMP)");
        execute("INSERT INTO nexora_executions (execution_id, context_json, state, started_at)"
                + " VALUES ('legacy', '{}', 'COMPLETED', TIMESTAMP '2026-01-01 00:00:00')");

        JdbcExecutionStore store = new JdbcExecutionStore(database);
        store.createSchedule(schedule("nightly"));

        assertEquals(List.of("0:true", "1:true", "2:true", "3:true"), history());
        assertEquals(
                ExecutionState.COMPLETED, store.findById("legacy").orElseThrow().state());
        assertTrue(store.findScheduleById("nightly").isPresent());
    }

    @Test
    void v1StoreGainsStepOutputColumnsKeepingItsSteps() throws SQLException {
        // A store migrated before step outputs existed: V1 applied, steps recorded, no output columns.
        new JdbcExecutionStore(database, V1_ONLY_LOCATION);
        execute("INSERT INTO nexora_executions (execution_id, context_json, state, started_at)"
                + " VALUES ('old', '{}', 'COMPLETED', TIMESTAMP '2026-01-01 00:00:00')");
        execute("INSERT INTO nexora_steps (execution_id, step_id, capability_id, idempotency_key, state, duration_ms)"
                + " VALUES ('old', 'charge', 'charge_card', 'key-1', 'COMPLETED', 12)");

        JdbcExecutionStore store = new JdbcExecutionStore(database);

        assertEquals(List.of("1:true", "2:true", "3:true"), history());
        StepRecord step = store.findById("old").orElseThrow().steps().get(0);
        assertEquals("key-1", step.idempotencyKey());
        assertEquals(Map.of(), step.output());
        assertFalse(step.outputTruncated());
    }

    @Test
    void v2StoreGainsTenantColumnsWithExistingRowsInTheDefaultTenant() throws SQLException {
        // A store from before tenants: V1 and V2 applied, one row in every table.
        new JdbcExecutionStore(database, THROUGH_V2_LOCATION);
        execute("INSERT INTO nexora_executions (execution_id, goal, context_json, state, started_at)"
                + " VALUES ('old', 'ship order', '{\"orderId\":\"o-1\"}', 'FAILED', TIMESTAMP '2026-01-01 00:00:00')");
        execute("INSERT INTO nexora_steps (execution_id, step_id, capability_id, idempotency_key, state, duration_ms,"
                + " output_json) VALUES ('old', 'charge', 'charge_card', 'key-1', 'COMPLETED', 12, '{\"ok\":true}')");
        execute("INSERT INTO nexora_webhook_deliveries (delivery_id, execution_id, url, status_code, attempt,"
                + " successful, timestamp) VALUES ('d-1', 'old', 'https://example.test', 200, 1, TRUE,"
                + " TIMESTAMP '2026-01-01 00:00:01')");
        execute("INSERT INTO nexora_dead_letters (id, execution_id, goal, context_json, failed_at)"
                + " VALUES ('dl-1', 'old', 'ship order', '{}', TIMESTAMP '2026-01-01 00:00:02')");
        execute(
                "INSERT INTO nexora_schedules (id, cron_expression, goal, created_at, next_fire_at)"
                        + " VALUES ('s-1', '0 * * * *', 'hourly', TIMESTAMP '2026-01-01 00:00:00', TIMESTAMP '2026-01-01 01:00:00')");

        JdbcExecutionStore store = new JdbcExecutionStore(database);

        assertEquals(List.of("1:true", "2:true", "3:true"), history());
        ExecutionRecord execution = store.findById("old").orElseThrow();
        assertEquals(TenantId.DEFAULT, execution.tenantId());
        assertEquals(Map.of("orderId", "o-1"), execution.context());
        assertEquals(Map.of("ok", true), execution.steps().get(0).output());
        assertEquals("key-1", execution.steps().get(0).idempotencyKey());
        assertEquals(1, store.getWebhookDeliveries("old").size());
        assertEquals(
                TenantId.DEFAULT, store.findDeadLetterById("dl-1").orElseThrow().tenantId());
        assertEquals(
                TenantId.DEFAULT, store.findScheduleById("s-1").orElseThrow().tenantId());
        assertEquals(
                List.of(TenantId.DEFAULT),
                distinctTenants(
                        "nexora_executions",
                        "nexora_steps",
                        "nexora_webhook_deliveries",
                        "nexora_dead_letters",
                        "nexora_schedules"));
    }

    @Test
    void schemaSharedWithAnotherApplicationStillGetsV1() throws SQLException {
        execute("CREATE TABLE host_app_orders (id INT PRIMARY KEY)");

        JdbcExecutionStore store = new JdbcExecutionStore(database);
        store.createExecution(
                new ExecutionRecord("e1", "trace", "goal", Map.of(), ExecutionState.RUNNING, T0, null, List.of()));

        assertTrue(store.findById("e1").isPresent());
    }

    @Test
    void schemaIsNotCurrentOnceAMigrationIsRecordedAsFailed() throws SQLException {
        JdbcExecutionStore store = new JdbcExecutionStore(database);

        execute("UPDATE \"nexora_schema_history\" SET \"success\" = FALSE WHERE \"version\" = '1'");

        assertFalse(store.isSchemaCurrent());
    }

    @Test
    void schemaIsNotCurrentWhenTheDatabaseIsOlderThanThisRelease() throws SQLException {
        JdbcExecutionStore store = new JdbcExecutionStore(database);

        // As after restoring a backup taken before the latest migration was applied.
        execute("DELETE FROM \"nexora_schema_history\" WHERE \"installed_rank\" ="
                + " (SELECT MAX(\"installed_rank\") FROM \"nexora_schema_history\")");

        assertFalse(store.isSchemaCurrent());
    }

    @Test
    void schemaIsNotCurrentWhenTheDatabaseIsUnreachable() {
        JdbcExecutionStore store = JdbcExecutionStore.h2InMemory();
        store.close();

        assertFalse(store.isSchemaCurrent());
    }

    @Test
    void callerPoolTooSmallToMigrateIsRejectedImmediately() {
        try (HikariDataSource onePooled = HikariCPDataSourceFactory.create(
                "jdbc:h2:mem:tiny_" + System.nanoTime(), PoolConfig.singleConnection())) {
            IllegalArgumentException error =
                    assertThrows(IllegalArgumentException.class, () -> new JdbcExecutionStore(onePooled));

            assertTrue(error.getMessage().contains("at least " + SchemaMigrator.MIGRATION_CONNECTIONS));
        }
    }

    @Test
    void urlMigrationErrorKeepsCredentialsMasked() {
        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> SchemaMigrator.migrate(
                        "jdbc:h2:mem:missing_" + System.nanoTime() + ";IFEXISTS=TRUE;PASSWORD=hunter2",
                        SchemaMigrator.DEFAULT_LOCATION));

        assertFalse(error.getMessage().contains("hunter2"), error.getMessage());
    }

    @Test
    void storeOwnedSingleConnectionPoolStillMigrates() {
        try (JdbcExecutionStore store = JdbcExecutionStore.h2InMemory()) {
            assertTrue(store.isSchemaCurrent());
        }
    }

    /** Versioned history rows as "version:success", in install order; skips Flyway's table-created marker. */
    private List<String> history() throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Connection conn = database.getConnection();
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT \"version\", \"success\" FROM \"nexora_schema_history\""
                        + " WHERE \"version\" IS NOT NULL ORDER BY \"installed_rank\"")) {
            while (rs.next()) {
                rows.add(rs.getString(1) + ":" + rs.getBoolean(2));
            }
        }
        return rows;
    }

    /** Every tenant id found across {@code tables}, without duplicates. */
    private List<String> distinctTenants(String... tables) throws SQLException {
        List<String> tenants = new ArrayList<>();
        try (Connection conn = database.getConnection();
                Statement stmt = conn.createStatement()) {
            for (String table : tables) {
                try (ResultSet rs = stmt.executeQuery("SELECT DISTINCT tenant_id FROM " + table)) {
                    while (rs.next()) {
                        if (!tenants.contains(rs.getString(1))) {
                            tenants.add(rs.getString(1));
                        }
                    }
                }
            }
        }
        return tenants;
    }

    private void execute(String sql) throws SQLException {
        try (Connection conn = database.getConnection();
                Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        }
    }

    private static ScheduleRecord schedule(String id) {
        return new ScheduleRecord(
                id,
                "0 0 * * *",
                "nightly cleanup",
                Map.of(),
                MissedFirePolicy.FIRE_ONCE,
                T0,
                null,
                T0.plusSeconds(3600),
                ScheduleStatus.NEVER_RUN,
                true);
    }
}
