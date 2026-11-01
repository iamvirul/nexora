package com.nexora.runtime.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

class CronSchedulerSlotTest {

    private static final CronExpression EVERY_MINUTE = CronExpression.parse("* * * * *");
    private static final Instant SLOT = Instant.parse("2026-10-01T10:05:00Z");

    @Test
    void earlyWakeUpMovesThePastTheClaimedSlotNotOntoIt() {
        ZonedDateTime justBefore = SLOT.minusMillis(400).atZone(ZoneOffset.UTC);

        assertEquals(
                SLOT.plusSeconds(60),
                CronScheduler.nextSlotAfterClaim(EVERY_MINUTE, SLOT, justBefore).toInstant());
    }

    @Test
    void lateWakeUpMovesToTheFirstSlotAfterNow() {
        ZonedDateTime muchLater = SLOT.plusSeconds(150).atZone(ZoneOffset.UTC);

        assertEquals(
                SLOT.plusSeconds(180),
                CronScheduler.nextSlotAfterClaim(EVERY_MINUTE, SLOT, muchLater).toInstant());
    }
}
