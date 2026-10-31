package com.nexora.persistence.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nexora.persistence.DeadLetterRecord;
import com.nexora.persistence.DeadLetterReviewState;
import com.nexora.persistence.ExecutionCursor;
import com.nexora.persistence.ExecutionFilter;
import com.nexora.persistence.ExecutionRecord;
import com.nexora.persistence.ExecutionState;
import com.nexora.persistence.MissedFirePolicy;
import com.nexora.persistence.ScheduleRecord;
import com.nexora.persistence.ScheduleStatus;
import com.nexora.persistence.StepOutput;
import com.nexora.persistence.StepRecord;
import com.nexora.persistence.StepState;
import com.nexora.persistence.WebhookDeliveryRecord;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Runs the store against a real PostgreSQL, where the H2 suites cannot catch dialect differences
 * (MERGE semantics, type coercion, LIKE escaping, concurrent upsert conflicts).
 *
 * <p>Tagged {@code integration}: failsafe runs it in {@code mvn verify}, {@code mvn test} skips it.
 * Needs a Docker daemon; without one it fails rather than skips, so CI cannot pass by not running it.
 * Skip it locally with {@code -DskipITs}.
 */
@Tag("integration")
@Testcontainers
class JdbcExecutionStorePostgresIT {

    // The oldest major DatabaseCompatibility accepts, so a feature only newer servers have fails here.
    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:15-alpine");
    // PostgreSQL TIMESTAMP keeps microseconds; whole seconds avoid asserting on precision loss.
    private static final Instant T0 = Instant.parse("2026-10-01T10:00:00Z");
    private static final int CONCURRENT_WRITERS = 10;
    private static final int RACE_ROUNDS = 50;
    private static final long DEADLOCK_TIMEOUT_SECONDS = 30;
    private static final List<String> NEXORA_TABLES = List.of(
            "nexora_executions",
            "nexora_steps",
            "nexora_webhook_deliveries",
            "nexora_dead_letters",
            "nexora_schedules");

    @Container
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(POSTGRES_IMAGE);

    private static JdbcExecutionStore store;

    @BeforeAll
    static void openStore() {
        // The URL-owning constructor is the production path: compatibility check, Flyway, then the pool.
        store = new JdbcExecutionStore(
                jdbcUrlWithCredentials(),
                new PoolConfig(
                        CONCURRENT_WRITERS,
                        1,
                        PoolConfig.DEFAULT_CONNECTION_TIMEOUT_MS,
                        PoolConfig.DEFAULT_IDLE_TIMEOUT_MS));
    }

    @AfterAll
    static void closeStore() {
        if (store != null) {
            store.close();
        }
    }

    @BeforeEach
    void truncateTables() throws SQLException {
        try (Connection conn = directConnection();
                Statement st = conn.createStatement()) {
            st.execute("TRUNCATE " + String.join(", ", NEXORA_TABLES));
        }
    }

    @Test
    void migrationsApplyCleanlyToAFreshDatabase() throws SQLException {
        assertTrue(store.isSchemaCurrent());
        assertTrue(store.isHealthy());
        try (Connection conn = directConnection()) {
            assertEquals(0, failedMigrations(conn));
            for (String table : NEXORA_TABLES) {
                assertTrue(tableExists(conn, table), table);
            }
            assertTrue(columnExists(conn, "nexora_steps", "output_json"));
            assertTrue(columnExists(conn, "nexora_steps", "output_truncated"));
            for (String table : NEXORA_TABLES) {
                assertTrue(columnExists(conn, table, "tenant_id"), table);
            }
        }
    }

    @Test
    void createdExecutionReadsBackWithItsContext() {
        Map<String, Object> context = Map.of("orderId", "o-1", "amount", 42, "tags", List.of("a", "b"));
        store.createExecution(ExecutionRecord.started("exec-1", "trace-1", "charge order", context, T0));

        ExecutionRecord stored = store.findById("exec-1").orElseThrow();

        assertEquals("trace-1", stored.traceId());
        assertEquals("charge order", stored.goal());
        assertEquals(context, stored.context());
        assertEquals(ExecutionState.RUNNING, stored.state());
        assertEquals(T0, stored.startedAt());
        assertNull(stored.completedAt());
        assertEquals(List.of(), stored.steps());
    }

    @Test
    void createExecutionTwiceOverwritesInsteadOfFailing() {
        store.createExecution(ExecutionRecord.started("exec-1", "trace-1", "first", Map.of(), T0));
        store.createExecution(new ExecutionRecord(
                "exec-1", "trace-2", "second", Map.of(), ExecutionState.FAILED, T0, T0.plusSeconds(3), List.of()));

        ExecutionRecord stored = store.findById("exec-1").orElseThrow();

        assertEquals("second", stored.goal());
        assertEquals(ExecutionState.FAILED, stored.state());
        assertEquals(T0.plusSeconds(3), stored.completedAt());
        assertEquals(1, store.count(ExecutionFilter.all()));
    }

    @Test
    void updateExecutionRecordsTheTerminalState() {
        store.createExecution(ExecutionRecord.started("exec-1", "trace", "goal", Map.of(), T0));

        store.updateExecution("exec-1", ExecutionState.COMPLETED, T0.plusSeconds(5));

        ExecutionRecord stored = store.findById("exec-1").orElseThrow();
        assertEquals(ExecutionState.COMPLETED, stored.state());
        assertEquals(T0.plusSeconds(5), stored.completedAt());
    }

    @Test
    void upsertStepKeepsTheStartKeyAndStoresTheOutput() {
        store.createExecution(ExecutionRecord.started("exec-1", "trace", "goal", Map.of(), T0));
        Map<String, Object> output = Map.of("chargeId", "ch-9", "nested", Map.of("cents", 1999));

        store.upsertStep("exec-1", StepRecord.started("charge", "payments", "key-1", T0));
        store.upsertStep(
                "exec-1",
                StepRecord.started("charge", "payments", null, T0)
                        .completed(T0.plusMillis(250), StepOutput.capture(output, StepOutput.DEFAULT_MAX_BYTES)));

        StepRecord step = store.findById("exec-1").orElseThrow().steps().get(0);
        assertEquals(StepState.COMPLETED, step.state());
        assertEquals("key-1", step.idempotencyKey());
        assertEquals(250, step.durationMs());
        assertEquals(T0.plusMillis(250), step.completedAt());
        assertEquals(output, step.output());
        assertFalse(step.outputTruncated());
    }

    @Test
    void failedStepKeepsItsFailureAndDropsNoFields() {
        store.createExecution(ExecutionRecord.started("exec-1", "trace", "goal", Map.of(), T0));

        store.upsertStep(
                "exec-1",
                StepRecord.started("charge", "payments", "key-1", T0)
                        .failed("CARD_DECLINED", "insufficient funds", T0.plusSeconds(1)));

        StepRecord step = store.findById("exec-1").orElseThrow().steps().get(0);
        assertEquals(StepState.FAILED, step.state());
        assertEquals("CARD_DECLINED", step.failureCode());
        assertEquals("insufficient funds", step.failureMessage());
        assertEquals(Map.of(), step.output());
    }

    @Test
    void findByIdReturnsEmptyForAnUnknownExecution() {
        assertTrue(store.findById("missing").isEmpty());
    }

    @Test
    void findRecentReturnsNewestFirstUpToTheLimit() {
        for (int i = 0; i < 5; i++) {
            store.createExecution(ExecutionRecord.started("exec-" + i, "trace", "goal", Map.of(), T0.plusSeconds(i)));
        }

        List<ExecutionRecord> recent = store.findRecent(3);

        assertEquals(
                List.of("exec-4", "exec-3", "exec-2"),
                recent.stream().map(ExecutionRecord::executionId).toList());
    }

    @Test
    void historyFilterMatchesLikeWildcardsLiterallyAndPagesByCursor() {
        store.createExecution(ExecutionRecord.started("exec-a", "t", "Ship 100% of_orders", Map.of(), T0));
        store.createExecution(ExecutionRecord.started("exec-b", "t", "Ship 100 orders", Map.of(), T0));
        store.createExecution(ExecutionRecord.started("exec-c", "t", "ship 100% OF_ORDERS again", Map.of(), T0));

        ExecutionFilter filter = new ExecutionFilter(Set.of(ExecutionState.RUNNING), "100% of_", null, null, null, 1);
        List<ExecutionRecord> firstPage = store.findByFilter(filter);
        List<ExecutionRecord> secondPage =
                store.findByFilter(filter.withAfter(ExecutionCursor.after(firstPage.get(0))));

        assertEquals(2, store.count(filter));
        assertEquals(
                List.of("exec-c"),
                firstPage.stream().map(ExecutionRecord::executionId).toList());
        assertEquals(
                List.of("exec-a"),
                secondPage.stream().map(ExecutionRecord::executionId).toList());
    }

    @Test
    void webhookDeliveriesReadBackInTimestampOrder() {
        store.createExecution(ExecutionRecord.started("exec-1", "trace", "goal", Map.of(), T0));
        store.recordWebhookDelivery(new WebhookDeliveryRecord(
                "d-2", "exec-1", "https://example.test/hook", 200, 2, true, T0.plusSeconds(2), null));
        store.recordWebhookDelivery(new WebhookDeliveryRecord(
                "d-1", "exec-1", "https://example.test/hook", 503, 1, false, T0.plusSeconds(1), "unavailable"));

        List<WebhookDeliveryRecord> deliveries = store.getWebhookDeliveries("exec-1");

        assertEquals(
                List.of("d-1", "d-2"),
                deliveries.stream().map(WebhookDeliveryRecord::deliveryId).toList());
        assertEquals("unavailable", deliveries.get(0).errorMessage());
    }

    @Test
    void deadLettersPageByOffsetAndRecordTheirResolution() {
        for (int i = 0; i < 3; i++) {
            store.createDeadLetter(DeadLetterRecord.pending(
                    "dl-" + i, "exec-" + i, "goal", Map.of("n", i), "TIMEOUT", "timed out", T0.plusSeconds(i)));
        }

        store.updateDeadLetterState("dl-2", DeadLetterReviewState.RESOLVED, "replayed manually");

        assertEquals(
                List.of("dl-1"),
                store.findDeadLetters(DeadLetterReviewState.PENDING, 0, 1).stream()
                        .map(DeadLetterRecord::id)
                        .toList());
        assertEquals(
                List.of("dl-0"),
                store.findDeadLetters(DeadLetterReviewState.PENDING, 1, 1).stream()
                        .map(DeadLetterRecord::id)
                        .toList());
        DeadLetterRecord resolved = store.findDeadLetterById("dl-2").orElseThrow();
        assertEquals(DeadLetterReviewState.RESOLVED, resolved.reviewState());
        assertEquals("replayed manually", resolved.resolveReason());
        assertEquals(Map.of("n", 2), resolved.context());
    }

    @Test
    void schedulesTrackFiringAndDeactivation() {
        store.createSchedule(new ScheduleRecord(
                "s-1",
                "0 * * * *",
                "hourly report",
                Map.of(),
                MissedFirePolicy.FIRE_ONCE,
                T0,
                null,
                T0.plusSeconds(3600),
                null,
                true));

        store.updateScheduleLastFired("s-1", T0.plusSeconds(3600), T0.plusSeconds(7200));
        store.updateScheduleStatus("s-1", ScheduleStatus.FIRED);
        ScheduleRecord fired = store.findScheduleById("s-1").orElseThrow();
        store.deactivateSchedule("s-1");

        assertEquals(T0.plusSeconds(3600), fired.lastFiredAt());
        assertEquals(T0.plusSeconds(7200), fired.nextFireAt());
        assertEquals(ScheduleStatus.FIRED, fired.lastStatus());
        assertEquals(List.of(), store.findActiveSchedules());
        assertFalse(store.findScheduleById("s-1").orElseThrow().active());
    }

    @Test
    void tenantScopedReadsNeverReturnAnotherTenantsRows() {
        store.createExecution(ExecutionRecord.started("a-1", "t", "ship order", Map.of(), T0, "acme"));
        store.createExecution(ExecutionRecord.started("g-1", "t", "ship order", Map.of(), T0.plusSeconds(1), "globex"));
        store.createDeadLetter(DeadLetterRecord.pending("dl-a", "a-1", "g", Map.of(), "X", "x", T0, "acme"));
        store.createDeadLetter(DeadLetterRecord.pending("dl-g", "g-1", "g", Map.of(), "X", "x", T0, "globex"));

        ExecutionFilter acme = ExecutionFilter.all().forTenant("acme");

        assertEquals(
                List.of("a-1"),
                store.findByFilter(acme).stream()
                        .map(ExecutionRecord::executionId)
                        .toList());
        assertEquals(1, store.count(acme));
        assertTrue(store.findById("globex", "a-1").isEmpty());
        assertEquals(
                List.of("dl-a"),
                store.findDeadLetters("acme", null, 0, 10).stream()
                        .map(DeadLetterRecord::id)
                        .toList());
    }

    @Test
    void stepsAndWebhookDeliveriesCopyTheTenantOfTheirExecution() throws SQLException {
        store.createExecution(ExecutionRecord.started("a-1", "t", "goal", Map.of(), T0, "acme"));
        store.upsertStep("a-1", StepRecord.started("charge", "cap", "key-1", T0));
        store.upsertStep("a-1", StepRecord.started("charge", "cap", null, T0).completed(T0.plusSeconds(1)));
        store.recordWebhookDelivery(
                new WebhookDeliveryRecord("d-1", "a-1", "https://example.test/hook", 200, 1, true, T0, null));

        StepRecord step = store.findById("acme", "a-1").orElseThrow().steps().get(0);
        assertEquals("key-1", step.idempotencyKey());
        assertEquals(StepState.COMPLETED, step.state());
        try (Connection conn = directConnection()) {
            assertEquals("acme", tenantOf(conn, "SELECT tenant_id FROM nexora_steps WHERE execution_id = 'a-1'"));
            assertEquals(
                    "acme",
                    tenantOf(conn, "SELECT tenant_id FROM nexora_webhook_deliveries WHERE delivery_id = 'd-1'"));
        }
    }

    @Test
    void tenConcurrentWritersCompleteWithoutDeadlockOrLostWrites() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(CONCURRENT_WRITERS);
        try {
            CyclicBarrier startGate = new CyclicBarrier(CONCURRENT_WRITERS);
            List<Future<ExecutionRecord>> results = new ArrayList<>();
            for (int i = 0; i < CONCURRENT_WRITERS; i++) {
                String executionId = "exec-" + i;
                results.add(workers.submit(() -> {
                    startGate.await();
                    return runExecutionLifecycle(executionId);
                }));
            }

            for (Future<ExecutionRecord> result : results) {
                ExecutionRecord stored = result.get(DEADLOCK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                assertEquals(ExecutionState.COMPLETED, stored.state());
                assertEquals(2, stored.steps().size());
                assertTrue(stored.steps().stream().allMatch(s -> s.state() == StepState.COMPLETED));
                assertTrue(stored.steps().stream().allMatch(s -> s.idempotencyKey() != null));
            }
            assertEquals(CONCURRENT_WRITERS, store.count(ExecutionFilter.all()));
        } finally {
            workers.shutdownNow();
        }
    }

    /**
     * Under READ COMMITTED, PostgreSQL MERGEs racing on a missing key can both take WHEN NOT MATCHED;
     * the loser fails with 23505 and the store must retry it as an update rather than drop the write.
     */
    @Test
    void concurrentUpsertsOnANewStepNeverLoseTheIdempotencyKey() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(CONCURRENT_WRITERS);
        try {
            List<String> lostKeys = new ArrayList<>();
            for (int round = 0; round < RACE_ROUNDS; round++) {
                String executionId = "race-" + round;
                store.createExecution(ExecutionRecord.started(executionId, "trace", "goal", Map.of(), T0));
                raceOnNewStep(workers, executionId);
                List<StepRecord> steps =
                        store.findById(executionId).orElseThrow().steps();
                if (steps.size() != 1 || steps.get(0).idempotencyKey() == null) {
                    lostKeys.add(executionId);
                }
            }
            assertEquals(List.of(), lostKeys);
        } finally {
            workers.shutdownNow();
        }
    }

    /** One writer records the step start with a key, the rest write keyless updates, all released at once. */
    private static void raceOnNewStep(ExecutorService workers, String executionId) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(CONCURRENT_WRITERS);
        List<Future<?>> writes = new ArrayList<>();
        for (int i = 0; i < CONCURRENT_WRITERS; i++) {
            String key = i == 0 ? executionId + "-key" : null;
            writes.add(workers.submit(() -> {
                barrier.await();
                store.upsertStep(executionId, StepRecord.started("step", "cap", key, T0));
                return null;
            }));
        }
        for (Future<?> write : writes) {
            write.get(DEADLOCK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
    }

    /** Mirrors the engine's write pattern: create, step start, step completion (no key), finish, read back. */
    private static ExecutionRecord runExecutionLifecycle(String executionId) {
        store.createExecution(ExecutionRecord.started(executionId, "trace", "goal", Map.of(), T0));
        for (String stepId : List.of("reserve", "charge")) {
            store.upsertStep(executionId, StepRecord.started(stepId, "cap", executionId + "-" + stepId, T0));
            store.upsertStep(
                    executionId, StepRecord.started(stepId, "cap", null, T0).completed(T0.plusMillis(5)));
        }
        store.updateExecution(executionId, ExecutionState.COMPLETED, T0.plusSeconds(1));
        store.findRecent(CONCURRENT_WRITERS);
        return store.findById(executionId).orElseThrow();
    }

    private static String jdbcUrlWithCredentials() {
        String url = postgres.getJdbcUrl();
        String separator = url.contains("?") ? "&" : "?";
        return url + separator + "user=" + postgres.getUsername() + "&password=" + postgres.getPassword();
    }

    private static Connection directConnection() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private static String tenantOf(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement();
                ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next(), sql);
            return rs.getString(1);
        }
    }

    private static int failedMigrations(Connection conn) throws SQLException {
        String sql = "SELECT COUNT(*) FROM \"" + SchemaMigrator.HISTORY_TABLE + "\" WHERE NOT \"success\"";
        try (Statement st = conn.createStatement();
                ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static boolean tableExists(Connection conn, String table) throws SQLException {
        String sql = "SELECT 1 FROM information_schema.tables WHERE table_name = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static boolean columnExists(Connection conn, String table, String column) throws SQLException {
        String sql = "SELECT 1 FROM information_schema.columns WHERE table_name = ? AND column_name = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }
}
