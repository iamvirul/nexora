package com.nexora.persistence;

import com.nexora.persistence.jdbc.JdbcExecutionStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class JdbcDeadLetterStoreTest {

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
    void createAndFindDeadLetter() {
        String id = UUID.randomUUID().toString();
        DeadLetterRecord dl = DeadLetterRecord.pending(
                id, "exec-1", "my goal", Map.of("key", "value"),
                "STEP_FAILED", "step blow up", Instant.now());

        store.createDeadLetter(dl);

        Optional<DeadLetterRecord> found = store.findDeadLetterById(id);
        assertTrue(found.isPresent());
        assertEquals(id, found.get().id());
        assertEquals("exec-1", found.get().executionId());
        assertEquals("my goal", found.get().goal());
        assertEquals("STEP_FAILED", found.get().failureCode());
        assertEquals(DeadLetterReviewState.PENDING, found.get().reviewState());
        assertNull(found.get().resolveReason());
    }

    @Test
    void findDeadLetterByIdReturnsEmptyWhenMissing() {
        Optional<DeadLetterRecord> result = store.findDeadLetterById("nonexistent");
        assertTrue(result.isEmpty());
    }

    @Test
    void findDeadLettersFiltersByState() {
        Instant now = Instant.now();
        store.createDeadLetter(DeadLetterRecord.pending(
                UUID.randomUUID().toString(), "e1", "goal1", Map.of(), "STEP_FAILED", null, now));
        store.createDeadLetter(DeadLetterRecord.pending(
                UUID.randomUUID().toString(), "e2", "goal2", Map.of(), "STEP_FAILED", null, now));

        String resolvedId = UUID.randomUUID().toString();
        store.createDeadLetter(DeadLetterRecord.pending(
                resolvedId, "e3", "goal3", Map.of(), "STEP_FAILED", null, now));
        store.updateDeadLetterState(resolvedId, DeadLetterReviewState.RESOLVED, "fixed");

        List<DeadLetterRecord> pending = store.findDeadLetters(DeadLetterReviewState.PENDING, 0, 10);
        assertEquals(2, pending.size());
        assertTrue(pending.stream().allMatch(d -> d.reviewState() == DeadLetterReviewState.PENDING));

        List<DeadLetterRecord> resolved = store.findDeadLetters(DeadLetterReviewState.RESOLVED, 0, 10);
        assertEquals(1, resolved.size());
        assertEquals("fixed", resolved.get(0).resolveReason());
    }

    @Test
    void findDeadLettersReturnsAllWhenStateIsNull() {
        Instant now = Instant.now();
        String id1 = UUID.randomUUID().toString();
        String id2 = UUID.randomUUID().toString();
        store.createDeadLetter(DeadLetterRecord.pending(id1, "e1", "g1", Map.of(), "CODE", null, now));
        store.createDeadLetter(DeadLetterRecord.pending(id2, "e2", "g2", Map.of(), "CODE", null, now));
        store.updateDeadLetterState(id2, DeadLetterReviewState.REPLAYED, null);

        List<DeadLetterRecord> all = store.findDeadLetters(null, 0, 10);
        assertEquals(2, all.size());
    }

    @Test
    void updateDeadLetterStateTransitionsCorrectly() {
        String id = UUID.randomUUID().toString();
        store.createDeadLetter(DeadLetterRecord.pending(
                id, "exec-x", "goal", Map.of(), "STEP_FAILED", null, Instant.now()));

        store.updateDeadLetterState(id, DeadLetterReviewState.RESOLVED, "manually reviewed");

        DeadLetterRecord updated = store.findDeadLetterById(id).orElseThrow();
        assertEquals(DeadLetterReviewState.RESOLVED, updated.reviewState());
        assertEquals("manually reviewed", updated.resolveReason());
    }

    @Test
    void findDeadLettersPagination() {
        Instant now = Instant.now();
        for (int i = 0; i < 5; i++) {
            store.createDeadLetter(DeadLetterRecord.pending(
                    UUID.randomUUID().toString(), "e" + i, "goal" + i,
                    Map.of(), "CODE", null, now));
        }

        List<DeadLetterRecord> page0 = store.findDeadLetters(DeadLetterReviewState.PENDING, 0, 3);
        List<DeadLetterRecord> page1 = store.findDeadLetters(DeadLetterReviewState.PENDING, 3, 3);

        assertEquals(3, page0.size());
        assertEquals(2, page1.size());
    }

    @Test
    void createDeadLetterPreservesContext() {
        Map<String, Object> ctx = Map.of("orderId", "ORD-123", "amount", 99);
        String id = UUID.randomUUID().toString();
        store.createDeadLetter(DeadLetterRecord.pending(
                id, "exec-ctx", "goal", ctx, "STEP_FAILED", null, Instant.now()));

        DeadLetterRecord found = store.findDeadLetterById(id).orElseThrow();
        assertEquals("ORD-123", found.context().get("orderId"));
    }
}
