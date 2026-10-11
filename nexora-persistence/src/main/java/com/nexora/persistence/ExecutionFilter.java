package com.nexora.persistence;

import java.time.Instant;
import java.util.Set;

/**
 * Query over the execution history. Every criterion is optional; results are ordered newest
 * first ({@code started_at DESC, execution_id DESC}).
 *
 * @param states       match any of these states; empty matches all
 * @param goalContains case-insensitive substring of the goal; {@code null} matches all
 * @param from         inclusive lower bound on {@code startedAt}; {@code null} for none
 * @param to           exclusive upper bound on {@code startedAt}; {@code null} for none
 * @param after        return only executions after this position; {@code null} starts at the newest
 * @param limit        maximum rows to return, 1 to {@value #MAX_LIMIT}
 */
public record ExecutionFilter(
        Set<ExecutionState> states, String goalContains, Instant from, Instant to, ExecutionCursor after, int limit) {
    public static final int DEFAULT_LIMIT = 20;
    // One above the API's max page size so callers can fetch limit + 1 to detect a next page.
    public static final int MAX_LIMIT = 101;

    public ExecutionFilter {
        states = states == null ? Set.of() : Set.copyOf(states);
        goalContains = goalContains == null || goalContains.isBlank() ? null : goalContains;
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT + ", got: " + limit);
        }
        if (from != null && to != null && !from.isBefore(to)) {
            throw new IllegalArgumentException("from must be before to");
        }
    }

    /** All executions, newest first, default page size. */
    public static ExecutionFilter all() {
        return new ExecutionFilter(Set.of(), null, null, null, null, DEFAULT_LIMIT);
    }

    public ExecutionFilter withAfter(ExecutionCursor cursor) {
        return new ExecutionFilter(states, goalContains, from, to, cursor, limit);
    }

    public ExecutionFilter withLimit(int newLimit) {
        return new ExecutionFilter(states, goalContains, from, to, after, newLimit);
    }
}
