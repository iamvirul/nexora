package com.nexora.runtime.scheduler;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.NavigableSet;
import java.util.TreeSet;

/**
 * Minimal 5-field UNIX cron expression parser and next-fire-time calculator.
 *
 * Field order: minute  hour  day-of-month  month  day-of-week
 * Supported syntax per field: *, N, N-M, N/step, *{@literal /}step, N,M,...
 * Day-of-week: 0=Sunday, 1=Monday, ..., 6=Saturday (7 is also accepted as Sunday).
 *
 * Day matching: if both day-of-month and day-of-week are restricted (non-*),
 * a day matches if EITHER constraint is satisfied (standard UNIX cron OR semantics).
 */
public final class CronExpression {

    private final String expression;
    private final NavigableSet<Integer> minutes;
    private final NavigableSet<Integer> hours;
    private final NavigableSet<Integer> daysOfMonth;
    private final NavigableSet<Integer> months;
    private final NavigableSet<Integer> daysOfWeek;
    private final boolean anyDayOfMonth;
    private final boolean anyDayOfWeek;

    public static CronExpression parse(String expression) {
        return new CronExpression(expression);
    }

    private CronExpression(String expression) {
        this.expression = expression.trim();
        String[] fields = this.expression.split("\\s+");
        if (fields.length != 5) {
            throw new IllegalArgumentException(
                "Cron expression must have exactly 5 fields (got " + fields.length + "): " + expression);
        }
        this.minutes      = parseField(fields[0], 0, 59);
        this.hours        = parseField(fields[1], 0, 23);
        this.daysOfMonth  = parseField(fields[2], 1, 31);
        this.months       = parseField(fields[3], 1, 12);
        this.daysOfWeek   = normalizeDow(parseField(fields[4], 0, 7));
        this.anyDayOfMonth = "*".equals(fields[2]);
        this.anyDayOfWeek  = "*".equals(fields[4]);
    }

    /** Returns the next fire time strictly after {@code from}, in UTC. */
    public ZonedDateTime next(ZonedDateTime from) {
        ZonedDateTime candidate = from.withZoneSameInstant(ZoneOffset.UTC)
                                      .truncatedTo(ChronoUnit.MINUTES)
                                      .plusMinutes(1);
        ZonedDateTime limit = candidate.plusYears(4);

        while (candidate.isBefore(limit)) {
            if (!months.contains(candidate.getMonthValue())) {
                candidate = candidate.withDayOfMonth(1).withHour(0).withMinute(0)
                                     .plusMonths(1);
                continue;
            }
            if (!dayMatches(candidate)) {
                candidate = candidate.withHour(0).withMinute(0).plusDays(1);
                continue;
            }
            if (!hours.contains(candidate.getHour())) {
                candidate = candidate.withMinute(0).plusHours(1);
                continue;
            }
            if (!minutes.contains(candidate.getMinute())) {
                candidate = candidate.plusMinutes(1);
                continue;
            }
            return candidate;
        }
        throw new IllegalStateException(
            "No next fire time found within 4 years for expression: " + expression);
    }

    private boolean dayMatches(ZonedDateTime dt) {
        int dom = dt.getDayOfMonth();
        int jdow = dt.getDayOfWeek().getValue(); // 1=Mon..7=Sun
        int dow = jdow == 7 ? 0 : jdow;          // convert to cron 0=Sun

        if (anyDayOfMonth && anyDayOfWeek) return true;
        if (anyDayOfMonth) return daysOfWeek.contains(dow);
        if (anyDayOfWeek)  return daysOfMonth.contains(dom);
        // both restricted: OR semantics
        return daysOfMonth.contains(dom) || daysOfWeek.contains(dow);
    }

    private static NavigableSet<Integer> parseField(String field, int min, int max) {
        NavigableSet<Integer> result = new TreeSet<>();
        for (String part : field.split(",")) {
            if (part.contains("/")) {
                String[] sp = part.split("/", 2);
                int step = Integer.parseInt(sp[1]);
                if (step < 1) throw new IllegalArgumentException("Step must be >= 1: " + part);
                int start = "*".equals(sp[0]) ? min : Integer.parseInt(sp[0]);
                if (start < min || start > max)
                    throw new IllegalArgumentException("Value " + start + " out of range [" + min + "," + max + "] in: " + part);
                for (int i = start; i <= max; i += step) result.add(i);
            } else if (part.contains("-")) {
                String[] rp = part.split("-", 2);
                int from = Integer.parseInt(rp[0]);
                int to   = Integer.parseInt(rp[1]);
                if (from < min || from > max)
                    throw new IllegalArgumentException("Value " + from + " out of range [" + min + "," + max + "] in: " + part);
                if (to < min || to > max)
                    throw new IllegalArgumentException("Value " + to + " out of range [" + min + "," + max + "] in: " + part);
                if (from > to)
                    throw new IllegalArgumentException("Range start " + from + " > end " + to + " in: " + part);
                for (int i = from; i <= to; i++) result.add(i);
            } else if ("*".equals(part)) {
                for (int i = min; i <= max; i++) result.add(i);
            } else {
                int val = Integer.parseInt(part);
                if (val < min || val > max)
                    throw new IllegalArgumentException("Value " + val + " out of range [" + min + "," + max + "] in field: " + field);
                result.add(val);
            }
        }
        return result;
    }

    /** Normalize day-of-week so that 7 (Sunday) maps to 0. */
    private static NavigableSet<Integer> normalizeDow(NavigableSet<Integer> set) {
        if (set.contains(7)) {
            set.remove(7);
            set.add(0);
        }
        return set;
    }

    @Override
    public String toString() {
        return expression;
    }
}
