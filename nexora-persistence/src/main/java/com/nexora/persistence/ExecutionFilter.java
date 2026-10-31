package com.nexora.persistence;

import com.nexora.core.tenant.TenantId;
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
 * @param tenantId     match only this tenant's executions; {@code null} matches every tenant
 */
public record ExecutionFilter(
        Set<ExecutionState> states,
        String goalContains,
        Instant from,
        Instant to,
        ExecutionCursor after,
        int limit,
        String tenantId) {
    public static final int DEFAULT_LIMIT = 20;
    // One above the API's max page size so callers can fetch limit + 1 to detect a next page.
    public static final int MAX_LIMIT = 101;

    public ExecutionFilter {
        states = states == null ? Set.of() : Set.copyOf(states);
        if (tenantId != null) {
            TenantId.requireValid(tenantId);
        }
        goalContains = goalContains == null || goalContains.isBlank() ? null : goalContains;
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT + ", got: " + limit);
        }
        if (from != null && to != null && !from.isBefore(to)) {
            throw new IllegalArgumentException("from must be before to");
        }
    }

    /** A filter over every tenant's executions. */
    public ExecutionFilter(
            Set<ExecutionState> states,
            String goalContains,
            Instant from,
            Instant to,
            ExecutionCursor after,
            int limit) {
        this(states, goalContains, from, to, after, limit, null);
    }

    /** All executions, newest first, default page size. */
    public static ExecutionFilter all() {
        return new ExecutionFilter(Set.of(), null, null, null, null, DEFAULT_LIMIT, null);
    }

    public ExecutionFilter withAfter(ExecutionCursor cursor) {
        return new ExecutionFilter(states, goalContains, from, to, cursor, limit, tenantId);
    }

    public ExecutionFilter withLimit(int newLimit) {
        return new ExecutionFilter(states, goalContains, from, to, after, newLimit, tenantId);
    }

    /** This filter narrowed to one tenant; {@code null} widens it to every tenant again. */
    public ExecutionFilter forTenant(String newTenantId) {
        return new ExecutionFilter(states, goalContains, from, to, after, limit, newTenantId);
    }
}
