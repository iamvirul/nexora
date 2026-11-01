package com.nexora.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nexora.persistence.jdbc.JdbcExecutionStore;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ScheduleClaimTest {

    private static final Instant SLOT = Instant.parse("2026-10-01T10:00:00Z");
    private static final Instant NEXT_SLOT = SLOT.plusSeconds(60);

    private JdbcExecutionStore store;

    @BeforeEach
    void setUp() {
        store = JdbcExecutionStore.h2InMemory();
        store.createSchedule(new ScheduleRecord(
                "s-1",
                "* * * * *",
                "ping",
                Map.of(),
                MissedFirePolicy.FIRE_ONCE,
                SLOT.minusSeconds(60),
                null,
                SLOT,
                null,
                true));
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    @Test
    void firstClaimOfASlotWinsAndMovesTheSchedule() {
        assertTrue(store.claimScheduleFire("s-1", SLOT, SLOT, NEXT_SLOT));

        ScheduleRecord claimed = store.findScheduleById("s-1").orElseThrow();
        assertEquals(SLOT, claimed.lastFiredAt());
        assertEquals(NEXT_SLOT, claimed.nextFireAt());
    }

    @Test
    void secondClaimOfTheSameSlotLoses() {
        assertTrue(store.claimScheduleFire("s-1", SLOT, SLOT, NEXT_SLOT));

        assertFalse(store.claimScheduleFire("s-1", SLOT, SLOT.plusMillis(5), NEXT_SLOT));
        assertEquals(SLOT, store.findScheduleById("s-1").orElseThrow().lastFiredAt());
    }

    @Test
    void cancelledOrUnknownScheduleCannotBeClaimed() {
        store.deactivateSchedule("s-1");

        assertFalse(store.claimScheduleFire("s-1", SLOT, SLOT, NEXT_SLOT));
        assertFalse(store.claimScheduleFire("missing", SLOT, SLOT, NEXT_SLOT));
    }
}
