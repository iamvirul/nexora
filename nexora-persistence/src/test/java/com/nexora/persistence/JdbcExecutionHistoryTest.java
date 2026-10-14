package com.nexora.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nexora.persistence.jdbc.JdbcExecutionStore;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
        store.upsertStep(
                "a", StepRecord.started("charge", "charge_card", null, T0).completed(T0.plusMillis(30)));
        store.upsertStep("a", StepRecord.started("ship", "ship", "key-2", T0));
        store.upsertStep(
                "a", StepRecord.started("ship", "ship", null, T0).failed("DOWN", "carrier down", T0.plusMillis(9)));

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

        assertEquals(
                StepState.SKIPPED,
                store.findById("a").orElseThrow().steps().get(0).state());
    }

    @Test
    void completedStepOutputIsReadableThroughFindById() {
        insert("a", "goal", ExecutionState.COMPLETED, T0);
        store.upsertStep("a", StepRecord.started("charge", "charge_card", "key-1", T0));
        StepOutput output = StepOutput.capture(Map.of("paymentId", "p-1", "amount", 42), StepOutput.DEFAULT_MAX_BYTES);
        store.upsertStep(
                "a", StepRecord.started("charge", "charge_card", null, T0).completed(T0.plusMillis(30), output));

        StepRecord step = store.findById("a").orElseThrow().steps().get(0);

        assertEquals(Map.of("paymentId", "p-1", "amount", 42), step.output());
        assertFalse(step.outputTruncated());
        assertEquals("key-1", step.idempotencyKey());
    }

    @Test
    void stepsWithoutOutputReadBackAsAnEmptyMap() {
        insert("a", "goal", ExecutionState.FAILED, T0);
        store.upsertStep("a", StepRecord.started("run", "cap", "k1", T0));
        store.upsertStep("a", StepRecord.started("fail", "cap", "k2", T0).failed("DOWN", "down", T0.plusMillis(1)));
        store.upsertStep("a", StepRecord.started("none", "cap", "k3", T0).completed(T0.plusMillis(1)));

        for (StepRecord step : store.findById("a").orElseThrow().steps()) {
            assertEquals(Map.of(), step.output(), step.stepId());
            assertFalse(step.outputTruncated(), step.stepId());
        }
    }

    @Test
    void truncatedOutputKeepsItsPreviewAndFlag() {
        insert("a", "goal", ExecutionState.COMPLETED, T0);
        StepOutput output = StepOutput.capture(Map.of("blob", "x".repeat(5_000)), StepOutput.MIN_MAX_BYTES);
        store.upsertStep("a", StepRecord.started("big", "cap", "k1", T0).completed(T0.plusMillis(1), output));

        StepRecord step = store.findById("a").orElseThrow().steps().get(0);

        assertTrue(step.outputTruncated());
        assertEquals(output.value(), step.output());
    }

    @Test
    void outputTheStoreCannotWriteStillRecordsTheStep() {
        insert("a", "goal", ExecutionState.COMPLETED, T0);
        // Built by hand rather than through StepOutput, with a value plain JSON cannot hold.
        Map<String, Object> cyclic = new HashMap<>();
        cyclic.put("self", cyclic);
        StepRecord step = new StepRecord(
                "s", "cap", "k1", StepState.COMPLETED, null, null, T0, T0.plusMillis(1), 1, cyclic, false);

        store.upsertStep("a", step);

        StepRecord stored = store.findById("a").orElseThrow().steps().get(0);
        assertEquals(StepState.COMPLETED, stored.state());
        assertEquals(Map.of(), stored.output());
    }

    @Test
    void restartingAStepClearsTheOutputOfItsEarlierRun() {
        insert("a", "goal", ExecutionState.RUNNING, T0);
        StepOutput output = StepOutput.capture(Map.of("old", true), StepOutput.DEFAULT_MAX_BYTES);
        store.upsertStep("a", StepRecord.started("s", "cap", "k1", T0).completed(T0.plusMillis(1), output));
        store.upsertStep("a", StepRecord.started("s", "cap", "k1", T0.plusSeconds(1)));

        assertEquals(Map.of(), store.findById("a").orElseThrow().steps().get(0).output());
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

        assertEquals(
                List.of("later-4", "later-3", "later-2", "later-1", "later-0", "tie-3", "tie-2", "tie-1", "tie-0"),
                seen);
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

        assertThrows(
                IllegalStateException.class,
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
        store.createExecution(
                new ExecutionRecord(id, "trace-" + id, goal, Map.of(), state, startedAt, null, List.of()));
    }

    private static ExecutionFilter filter(Set<ExecutionState> states, String goal, Instant from, Instant to) {
        return new ExecutionFilter(states, goal, from, to, null, ExecutionFilter.DEFAULT_LIMIT);
    }

    private static List<String> ids(List<ExecutionRecord> records) {
        return records.stream().map(ExecutionRecord::executionId).toList();
    }
}
