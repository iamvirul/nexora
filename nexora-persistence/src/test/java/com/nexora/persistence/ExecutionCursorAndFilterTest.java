package com.nexora.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ExecutionCursorAndFilterTest {

    @Test
    void cursorRoundTripsThroughItsToken() {
        ExecutionCursor cursor = new ExecutionCursor(Instant.parse("2026-10-01T10:00:00.123456Z"), "exec-1");

        assertEquals(cursor, ExecutionCursor.decode(cursor.encode()));
    }

    @Test
    void cursorRejectsMalformedTokens() {
        String notBase64 = "%%%";
        String noSeparator = encode("2026-10-01T10:00:00Z");
        String badTimestamp = encode("yesterday|exec-1");
        String missingId = encode("2026-10-01T10:00:00Z|");

        for (String token : new String[] {notBase64, noSeparator, badTimestamp, missingId, "", "a".repeat(300)}) {
            assertThrows(IllegalArgumentException.class, () -> ExecutionCursor.decode(token), token);
        }
    }

    @Test
    void filterRejectsLimitOutOfRange() {
        assertThrows(IllegalArgumentException.class, () -> ExecutionFilter.all().withLimit(0));
        assertThrows(
                IllegalArgumentException.class, () -> ExecutionFilter.all().withLimit(ExecutionFilter.MAX_LIMIT + 1));
    }

    @Test
    void filterRejectsFromNotBeforeTo() {
        Instant t = Instant.parse("2026-10-01T10:00:00Z");

        assertThrows(
                IllegalArgumentException.class,
                () -> new ExecutionFilter(Set.of(), null, t, t, null, ExecutionFilter.DEFAULT_LIMIT));
    }

    @Test
    void filterTreatsBlankGoalAsNoGoalFilter() {
        ExecutionFilter filter = new ExecutionFilter(null, "  ", null, null, null, ExecutionFilter.DEFAULT_LIMIT);

        assertEquals(null, filter.goalContains());
        assertEquals(Set.of(), filter.states());
    }

    private static String encode(String raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
}
