package com.nexora.persistence;

import com.nexora.persistence.jdbc.JdbcExecutionStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcExecutionHistoryTest {

    private static final Instant T0 = Instant.parse("2026-10-01T10:00:00Z");

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
    void returnsExecutionsNewestFirst() {
        insert("a", "process order", ExecutionState.COMPLETED, T0);
        insert("b", "process order", ExecutionState.COMPLETED, T0.plusSeconds(60));
        insert("c", "process order", ExecutionState.COMPLETED, T0.plusSeconds(120));

        assertEquals(List.of("c", "b", "a"), ids(store.findByFilter(ExecutionFilter.all())));
    }

    @Test
    void summariesDoNotLoadSteps() {
        insert("a", "process order", ExecutionState.COMPLETED, T0);
        store.upsertStep("a", StepRecord.started("s1", "cap", "k1", T0).completed(T0.plusMillis(5)));

        assertTrue(store.findByFilter(ExecutionFilter.all()).get(0).steps().isEmpty());
        assertEquals(1, store.findById("a").orElseThrow().steps().size());
    }

    @Test
    void completingAStepKeepsItsRecordedIdempotencyKey() {
        insert("a", "goal", ExecutionState.FAILED, T0);
        store.upsertStep("a", StepRecord.started("charge", "charge_card", "key-1", T0));
        // Completion and failure events carry no key; the recorded one must survive them.
        store.upsertStep("a", StepRecord.started("charge", "charge_card", null, T0).completed(T0.plusMillis(30)));
        store.upsertStep("a", StepRecord.started("ship", "ship", "key-2", T0));
        store.upsertStep("a", StepRecord.started("ship", "ship", null, T0).failed("DOWN", "carrier down", T0.plusMillis(9)));

        List<StepRecord> steps = store.findById("a").orElseThrow().steps();

        assertEquals("key-1", stepById(steps, "charge").idempotencyKey());
        assertEquals(StepState.COMPLETED, stepById(steps, "charge").state());
        assertEquals("key-2", stepById(steps, "ship").idempotencyKey());
        assertEquals(StepState.FAILED, stepById(steps, "ship").state());
    }

    @Test
    void stepWithoutPriorRowIsInsertedEvenWithoutKey() {
        insert("a", "goal", ExecutionState.FAILED, T0);
        store.upsertStep("a", StepRecord.started("skip", "noop", null, T0).skipped(T0));

        assertEquals(StepState.SKIPPED, store.findById("a").orElseThrow().steps().get(0).state());
    }

    private static StepRecord stepById(List<StepRecord> steps, String stepId) {
        return steps.stream().filter(s -> s.stepId().equals(stepId)).findFirst().orElseThrow();
    }

    @Test
    void filtersByAnyOfTheGivenStates() {
        insert("ok", "goal", ExecutionState.COMPLETED, T0);
        insert("bad", "goal", ExecutionState.FAILED, T0.plusSeconds(1));
        insert("stop", "goal", ExecutionState.CANCELLED, T0.plusSeconds(2));

        ExecutionFilter filter = filter(Set.of(ExecutionState.FAILED, ExecutionState.CANCELLED), null, null, null);

        assertEquals(List.of("stop", "bad"), ids(store.findByFilter(filter)));
        assertEquals(2, store.count(filter));
    }

    @Test
    void goalFilterIsCaseInsensitiveSubstring() {
        insert("pay", "Process Payment", ExecutionState.COMPLETED, T0);
        insert("ship", "ship order", ExecutionState.COMPLETED, T0.plusSeconds(1));

        assertEquals(List.of("pay"), ids(store.findByFilter(filter(Set.of(), "payment", null, null))));
    }

    @Test
    void goalFilterTreatsLikeWildcardsLiterally() {
        insert("pct", "discount 100% off", ExecutionState.COMPLETED, T0);
        insert("plain", "discount 100 off", ExecutionState.COMPLETED, T0.plusSeconds(1));
        insert("under", "a_b", ExecutionState.COMPLETED, T0.plusSeconds(2));
        insert("noUnder", "axb", ExecutionState.COMPLETED, T0.plusSeconds(3));

        assertEquals(List.of("pct"), ids(store.findByFilter(filter(Set.of(), "100%", null, null))));
        assertEquals(List.of("under"), ids(store.findByFilter(filter(Set.of(), "a_b", null, null))));
    }

    @Test
    void goalFilterIsNotInjectable() {
        insert("a", "process order", ExecutionState.COMPLETED, T0);

        ExecutionFilter hostile = filter(Set.of(), "x' OR '1'='1", null, null);

        assertEquals(List.of(), store.findByFilter(hostile));
        assertEquals(0, store.count(hostile));
        assertEquals(1, store.count(ExecutionFilter.all()));
    }

    @Test
    void timeRangeIsFromInclusiveToExclusiveAcrossOffsets() {
        insert("before", "goal", ExecutionState.COMPLETED, T0.minusSeconds(1));
        insert("atFrom", "goal", ExecutionState.COMPLETED, T0);
        insert("inside", "goal", ExecutionState.COMPLETED, T0.plusSeconds(1800));
        insert("atTo", "goal", ExecutionState.COMPLETED, T0.plusSeconds(3600));

        // 15:30+05:30 is 10:00Z and 16:30+05:30 is 11:00Z
        Instant from = OffsetDateTime.parse("2026-10-01T15:30:00+05:30").toInstant();
        Instant to = OffsetDateTime.parse("2026-10-01T16:30:00+05:30").toInstant();

        assertEquals(List.of("inside", "atFrom"), ids(store.findByFilter(filter(Set.of(), null, from, to))));
    }

    @Test
    void cursorPagesThroughEveryExecutionExactlyOnceIncludingTies() {
        // Four executions share a start time: the execution_id tiebreaker must keep pages stable.
        for (int i = 0; i < 4; i++) {
            insert("tie-" + i, "goal", ExecutionState.COMPLETED, T0);
        }
        for (int i = 0; i < 5; i++) {
            insert("later-" + i, "goal", ExecutionState.COMPLETED, T0.plusSeconds(i + 1));
        }

        List<String> seen = new ArrayList<>();
        ExecutionFilter page = ExecutionFilter.all().withLimit(2);
        List<ExecutionRecord> rows;
        do {
            rows = store.findByFilter(page);
            seen.addAll(ids(rows));
            if (!rows.isEmpty()) {
                page = page.withAfter(ExecutionCursor.after(rows.get(rows.size() - 1)));
            }
        } while (rows.size() == 2);

        assertEquals(List.of("later-4", "later-3", "later-2", "later-1", "later-0",
                "tie-3", "tie-2", "tie-1", "tie-0"), seen);
    }

    @Test
    void findByIdReturnsEmptyForUnknownExecution() {
        assertTrue(store.findById("missing").isEmpty());
    }

    @Test
    void findByIdReportsStoreFailureInsteadOfEmpty() {
        insert("a", "goal", ExecutionState.COMPLETED, T0);
        store.close(); // simulates the database becoming unreachable

        assertThrows(IllegalStateException.class, () -> store.findById("a"));
    }

    @Test
    void updateExecutionReportsStoreFailure() {
        insert("a", "goal", ExecutionState.RUNNING, T0);
        store.close();

        assertThrows(IllegalStateException.class,
                () -> store.updateExecution("a", ExecutionState.COMPLETED, T0.plusSeconds(1)));
    }

    @Test
    void historyQueriesReportStoreFailure() {
        store.close();

        assertThrows(IllegalStateException.class, () -> store.findByFilter(ExecutionFilter.all()));
        assertThrows(IllegalStateException.class, () -> store.count(ExecutionFilter.all()));
    }

    @Test
    void countIgnoresCursorAndLimit() {
        for (int i = 0; i < 5; i++) {
            insert("e" + i, "goal", ExecutionState.COMPLETED, T0.plusSeconds(i));
        }
        ExecutionRecord newest = store.findByFilter(ExecutionFilter.all()).get(0);

        ExecutionFilter paged = ExecutionFilter.all().withLimit(1).withAfter(ExecutionCursor.after(newest));

        assertEquals(1, store.findByFilter(paged).size());
        assertEquals(5, store.count(paged));
    }

    private void insert(String id, String goal, ExecutionState state, Instant startedAt) {
        store.createExecution(new ExecutionRecord(id, "trace-" + id, goal, Map.of(), state, startedAt, null, List.of()));
    }

    private static ExecutionFilter filter(Set<ExecutionState> states, String goal, Instant from, Instant to) {
        return new ExecutionFilter(states, goal, from, to, null, ExecutionFilter.DEFAULT_LIMIT);
    }

    private static List<String> ids(List<ExecutionRecord> records) {
        return records.stream().map(ExecutionRecord::executionId).toList();
    }
}
