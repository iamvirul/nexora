package com.nexora.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nexora.core.tenant.TenantId;
import com.nexora.persistence.jdbc.JdbcExecutionStore;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TenantIsolationTest {

    private static final Instant T0 = Instant.parse("2026-10-01T10:00:00Z");
    private static final String ACME = "acme";
    private static final String GLOBEX = "globex";

    private JdbcExecutionStore store;

    @BeforeEach
    void setUp() {
        store = JdbcExecutionStore.h2InMemory();
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    @Test
    void executionRecordsKeepTheirTenant() {
        store.createExecution(ExecutionRecord.started("a-1", "t", "goal", Map.of(), T0, ACME));

        assertEquals(ACME, store.findById("a-1").orElseThrow().tenantId());
    }

    @Test
    void executionWithoutTenantBelongsToTheDefaultTenant() {
        store.createExecution(ExecutionRecord.started("d-1", "t", "goal", Map.of(), T0));

        assertEquals(TenantId.DEFAULT, store.findById("d-1").orElseThrow().tenantId());
    }

    @Test
    void tenantScopedLookupTreatsAnotherTenantsExecutionAsAbsent() {
        store.createExecution(ExecutionRecord.started("a-1", "t", "goal", Map.of(), T0, ACME));

        assertTrue(store.findById(ACME, "a-1").isPresent());
        assertTrue(store.findById(GLOBEX, "a-1").isEmpty());
    }

    @Test
    void historyForOneTenantNeverReturnsAnotherTenantsExecutions() {
        store.createExecution(ExecutionRecord.started("a-1", "t", "ship order", Map.of(), T0, ACME));
        store.createExecution(ExecutionRecord.started("a-2", "t", "ship order", Map.of(), T0.plusSeconds(1), ACME));
        store.createExecution(ExecutionRecord.started("g-1", "t", "ship order", Map.of(), T0.plusSeconds(2), GLOBEX));

        ExecutionFilter acme = ExecutionFilter.all().forTenant(ACME);
        ExecutionFilter globex = ExecutionFilter.all().forTenant(GLOBEX);

        assertEquals(List.of("a-2", "a-1"), ids(store.findByFilter(acme)));
        assertEquals(List.of("g-1"), ids(store.findByFilter(globex)));
        assertEquals(2, store.count(acme));
        assertEquals(1, store.count(globex));
        assertEquals(3, store.count(ExecutionFilter.all()));
    }

    @Test
    void historyCursorStaysWithinTheTenant() {
        store.createExecution(ExecutionRecord.started("a-1", "t", "goal", Map.of(), T0, ACME));
        store.createExecution(ExecutionRecord.started("g-1", "t", "goal", Map.of(), T0.plusSeconds(1), GLOBEX));
        store.createExecution(ExecutionRecord.started("a-2", "t", "goal", Map.of(), T0.plusSeconds(2), ACME));

        ExecutionFilter firstPage = ExecutionFilter.all().forTenant(ACME).withLimit(1);
        List<ExecutionRecord> first = store.findByFilter(firstPage);
        List<ExecutionRecord> second = store.findByFilter(firstPage.withAfter(ExecutionCursor.after(first.get(0))));

        assertEquals(List.of("a-2"), ids(first));
        assertEquals(List.of("a-1"), ids(second));
    }

    @Test
    void stepsTakeTheTenantOfTheirExecution() {
        store.createExecution(ExecutionRecord.started("a-1", "t", "goal", Map.of(), T0, ACME));
        store.upsertStep("a-1", StepRecord.started("charge", "cap", "key", T0));
        store.upsertStep("a-1", StepRecord.started("charge", "cap", null, T0).completed(T0.plusSeconds(1)));

        ExecutionRecord stored = store.findById(ACME, "a-1").orElseThrow();

        assertEquals(1, stored.steps().size());
        assertEquals(StepState.COMPLETED, stored.steps().get(0).state());
        assertEquals("key", stored.steps().get(0).idempotencyKey());
    }

    @Test
    void deadLettersPageWithinOneTenant() {
        store.createDeadLetter(DeadLetterRecord.pending("dl-a1", "a-1", "g", Map.of(), "X", "x", T0, ACME));
        store.createDeadLetter(DeadLetterRecord.pending("dl-g1", "g-1", "g", Map.of(), "X", "x", T0, GLOBEX));
        store.createDeadLetter(
                DeadLetterRecord.pending("dl-a2", "a-2", "g", Map.of(), "X", "x", T0.plusSeconds(1), ACME));

        assertEquals(List.of("dl-a2"), deadLetterIds(store.findDeadLetters(ACME, DeadLetterReviewState.PENDING, 0, 1)));
        assertEquals(List.of("dl-a1"), deadLetterIds(store.findDeadLetters(ACME, DeadLetterReviewState.PENDING, 1, 1)));
        assertEquals(List.of("dl-g1"), deadLetterIds(store.findDeadLetters(GLOBEX, null, 0, 10)));
        assertEquals(3, store.findDeadLetters(null, null, 0, 10).size());
        assertEquals(ACME, store.findDeadLetterById("dl-a1").orElseThrow().tenantId());
    }

    @Test
    void schedulesKeepTheirTenant() {
        store.createSchedule(new ScheduleRecord(
                "s-1",
                "0 * * * *",
                "hourly",
                Map.of(),
                MissedFirePolicy.SKIP,
                T0,
                null,
                T0.plusSeconds(3600),
                null,
                true,
                GLOBEX));

        assertEquals(GLOBEX, store.findScheduleById("s-1").orElseThrow().tenantId());
    }

    @Test
    void invalidTenantIdsAreRejectedBeforeReachingTheDatabase() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ExecutionRecord.started("x", "t", "goal", Map.of(), T0, "Acme Corp"));
        assertThrows(IllegalArgumentException.class, () -> ExecutionFilter.all().forTenant("acme'; DROP TABLE x"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ExecutionFilter(Set.of(), null, null, null, null, 10, "x".repeat(TenantId.MAX_LENGTH + 1)));
    }

    private static List<String> ids(List<ExecutionRecord> records) {
        return records.stream().map(ExecutionRecord::executionId).toList();
    }

    private static List<String> deadLetterIds(List<DeadLetterRecord> records) {
        return records.stream().map(DeadLetterRecord::id).toList();
    }
}
