package com.nexora.runtime.scheduler;

import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CronExpressionTest {

    @Test
    void everyMinute_firesOneMinuteAfterFrom() {
        CronExpression cron = CronExpression.parse("* * * * *");
        ZonedDateTime from = ZonedDateTime.of(2026, 1, 1, 12, 0, 0, 0, ZoneOffset.UTC);
        ZonedDateTime next = cron.next(from);
        assertThat(next).isEqualTo(ZonedDateTime.of(2026, 1, 1, 12, 1, 0, 0, ZoneOffset.UTC));
    }

    @Test
    void everyFiveMinutes_nextAlignedSlot() {
        CronExpression cron = CronExpression.parse("*/5 * * * *");
        ZonedDateTime from = ZonedDateTime.of(2026, 1, 1, 12, 3, 0, 0, ZoneOffset.UTC);
        ZonedDateTime next = cron.next(from);
        assertThat(next).isEqualTo(ZonedDateTime.of(2026, 1, 1, 12, 5, 0, 0, ZoneOffset.UTC));
    }

    @Test
    void dailyMidnight_nextDayWhenPastMidnight() {
        CronExpression cron = CronExpression.parse("0 0 * * *");
        ZonedDateTime from = ZonedDateTime.of(2026, 1, 1, 0, 1, 0, 0, ZoneOffset.UTC);
        ZonedDateTime next = cron.next(from);
        assertThat(next).isEqualTo(ZonedDateTime.of(2026, 1, 2, 0, 0, 0, 0, ZoneOffset.UTC));
    }

    @Test
    void dailyMidnight_sameDay_whenBeforeMidnight() {
        CronExpression cron = CronExpression.parse("0 0 * * *");
        ZonedDateTime from = ZonedDateTime.of(2025, 12, 31, 23, 59, 0, 0, ZoneOffset.UTC);
        ZonedDateTime next = cron.next(from);
        assertThat(next).isEqualTo(ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC));
    }

    @Test
    void specificHourAndMinute() {
        CronExpression cron = CronExpression.parse("30 9 * * *");
        ZonedDateTime from = ZonedDateTime.of(2026, 6, 15, 9, 29, 0, 0, ZoneOffset.UTC);
        ZonedDateTime next = cron.next(from);
        assertThat(next).isEqualTo(ZonedDateTime.of(2026, 6, 15, 9, 30, 0, 0, ZoneOffset.UTC));
    }

    @Test
    void specificHourAndMinute_wrapsToNextDay() {
        CronExpression cron = CronExpression.parse("30 9 * * *");
        ZonedDateTime from = ZonedDateTime.of(2026, 6, 15, 9, 31, 0, 0, ZoneOffset.UTC);
        ZonedDateTime next = cron.next(from);
        assertThat(next).isEqualTo(ZonedDateTime.of(2026, 6, 16, 9, 30, 0, 0, ZoneOffset.UTC));
    }

    @Test
    void firstOfMonthAtMidnight() {
        CronExpression cron = CronExpression.parse("0 0 1 * *");
        ZonedDateTime from = ZonedDateTime.of(2026, 1, 15, 0, 0, 0, 0, ZoneOffset.UTC);
        ZonedDateTime next = cron.next(from);
        assertThat(next).isEqualTo(ZonedDateTime.of(2026, 2, 1, 0, 0, 0, 0, ZoneOffset.UTC));
    }

    @Test
    void specificMonth() {
        CronExpression cron = CronExpression.parse("0 0 1 6 *");
        ZonedDateTime from = ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        ZonedDateTime next = cron.next(from);
        assertThat(next).isEqualTo(ZonedDateTime.of(2026, 6, 1, 0, 0, 0, 0, ZoneOffset.UTC));
    }

    @Test
    void commaList_selectsFirstMatchingMinute() {
        CronExpression cron = CronExpression.parse("0,30 * * * *");
        ZonedDateTime from = ZonedDateTime.of(2026, 1, 1, 12, 0, 0, 0, ZoneOffset.UTC);
        ZonedDateTime next = cron.next(from);
        assertThat(next).isEqualTo(ZonedDateTime.of(2026, 1, 1, 12, 30, 0, 0, ZoneOffset.UTC));
    }

    @Test
    void rangeField() {
        CronExpression cron = CronExpression.parse("0 9-17 * * *");
        ZonedDateTime from = ZonedDateTime.of(2026, 1, 1, 8, 59, 0, 0, ZoneOffset.UTC);
        ZonedDateTime next = cron.next(from);
        assertThat(next).isEqualTo(ZonedDateTime.of(2026, 1, 1, 9, 0, 0, 0, ZoneOffset.UTC));
    }

    @Test
    void invalidFieldCount_throws() {
        assertThatThrownBy(() -> CronExpression.parse("* * * *"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("5 fields");
    }

    @Test
    void dowSunday_zeroAndSeven_equivalent() {
        ZonedDateTime monday = ZonedDateTime.of(2026, 6, 29, 23, 59, 0, 0, ZoneOffset.UTC); // Monday
        ZonedDateTime nextUsing0 = CronExpression.parse("0 0 * * 0").next(monday);
        ZonedDateTime nextUsing7 = CronExpression.parse("0 0 * * 7").next(monday);
        assertThat(nextUsing0).isEqualTo(nextUsing7);
        // Both should land on Sunday
        assertThat(nextUsing0.getDayOfWeek().getValue()).isEqualTo(7); // Java: 7=SUNDAY
    }
}
