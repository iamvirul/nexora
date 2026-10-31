package com.nexora.core.tenant;

import java.util.regex.Pattern;

/**
 * Tenant identifiers. A tenant id names the namespace an execution, its history, its dead letters
 * and its schedules belong to. Work with no tenant belongs to {@link #DEFAULT}, which is all a
 * single-tenant deployment ever uses.
 *
 * <p>Ids are lowercase so {@code Acme} and {@code acme} can never become two tenants, and limited
 * to characters that are safe in a header, a log line, a URL and a {@code VARCHAR(64)} column.
 */
public final class TenantId {

    /** The tenant of work that names none. */
    public static final String DEFAULT = "default";

    public static final int MAX_LENGTH = 64;

    private static final Pattern VALID = Pattern.compile("[a-z0-9][a-z0-9_-]{0," + (MAX_LENGTH - 1) + "}");

    private TenantId() {}

    public static boolean isValid(String tenantId) {
        return tenantId != null && VALID.matcher(tenantId).matches();
    }

    /**
     * @return {@code tenantId} unchanged
     * @throws IllegalArgumentException if it is null or not a valid tenant id
     */
    public static String requireValid(String tenantId) {
        if (!isValid(tenantId)) {
            throw new IllegalArgumentException("Invalid tenant id: must be 1 to " + MAX_LENGTH
                    + " characters of a-z, 0-9, '-' or '_', starting with a letter or digit");
        }
        return tenantId;
    }

    /** {@link #DEFAULT} for null, otherwise {@link #requireValid(String)}. */
    public static String orDefault(String tenantId) {
        return tenantId == null ? DEFAULT : requireValid(tenantId);
    }
}
