package com.nexora.cli;

import com.nexora.persistence.ExecutionCursor;
import com.nexora.persistence.ExecutionFilter;
import com.nexora.persistence.ExecutionState;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Parses and validates the query string of {@code GET /api/executions}. Every rejection is an
 * {@link IllegalArgumentException} whose message is safe to return to the client as a 400.
 *
 * @param filter   the store query, with limit one above {@code pageSize} to detect a next page
 * @param pageSize rows the client asked for
 */
record ExecutionHistoryQuery(ExecutionFilter filter, int pageSize) {

    static final int MAX_PAGE_SIZE = ExecutionFilter.MAX_LIMIT - 1;
    private static final int MAX_GOAL_LENGTH = 200;

    static ExecutionHistoryQuery parse(String rawQuery) {
        Map<String, String> params = decode(rawQuery);
        int pageSize = parseLimit(params.get("limit"));
        ExecutionFilter filter = new ExecutionFilter(
                parseStates(params.get("status")),
                parseGoal(params.get("goal")),
                parseTimestamp("from", params.get("from")),
                parseTimestamp("to", params.get("to")),
                parseCursor(params.get("cursor")),
                pageSize + 1);
        return new ExecutionHistoryQuery(filter, pageSize);
    }

    private static Map<String, String> decode(String rawQuery) {
        Map<String, String> params = new HashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return params;
        }
        for (String pair : rawQuery.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv[0].isEmpty()) {
                continue;
            }
            // Keep empty values ("?limit=") so they are validated instead of silently defaulted.
            params.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                    kv.length == 2 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
        }
        return params;
    }

    private static int parseLimit(String raw) {
        if (raw == null) {
            return ExecutionFilter.DEFAULT_LIMIT;
        }
        try {
            int limit = Integer.parseInt(raw);
            if (limit >= 1 && limit <= MAX_PAGE_SIZE) {
                return limit;
            }
        } catch (NumberFormatException e) {
            // fall through to the shared message
        }
        throw new IllegalArgumentException("limit must be an integer between 1 and " + MAX_PAGE_SIZE);
    }

    private static Set<ExecutionState> parseStates(String raw) {
        if (raw == null) {
            return Set.of();
        }
        Set<ExecutionState> states = EnumSet.noneOf(ExecutionState.class);
        for (String name : raw.split(",")) {
            try {
                states.add(ExecutionState.valueOf(name.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid status '" + name.trim() + "'. Allowed: "
                        + Arrays.stream(ExecutionState.values()).map(Enum::name).collect(Collectors.joining(", ")));
            }
        }
        return states;
    }

    private static String parseGoal(String raw) {
        if (raw != null && raw.length() > MAX_GOAL_LENGTH) {
            throw new IllegalArgumentException("goal must be at most " + MAX_GOAL_LENGTH + " characters");
        }
        return raw;
    }

    private static Instant parseTimestamp(String name, String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(raw).toInstant();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(name + " must be an ISO-8601 timestamp with an offset, such as "
                    + "2026-10-01T10:00:00Z or 2026-10-01T15:30:00%2B05:30 (encode '+' as %2B)");
        }
    }

    private static ExecutionCursor parseCursor(String raw) {
        return raw == null ? null : ExecutionCursor.decode(raw);
    }
}
