package me.virul.nexora.api.auth;

import me.virul.nexora.core.tenant.TenantId;

/**
 * The tenant one API request acts for, as decided by {@link TenantResolver}.
 *
 * @param tenantId the only tenant the request may read or start work for; {@code null} on a
 *                 single-tenant server, where nothing is filtered and new work goes to
 *                 {@link TenantId#DEFAULT}
 */
public record TenantScope(String tenantId) {

    /** The scope of a request on a single-tenant server: every record is visible. */
    public static final TenantScope UNSCOPED = new TenantScope(null);

    /**
     * Validates the scope.
     *
     * @throws IllegalArgumentException if {@code tenantId} is not null and not a valid tenant id
     */
    public TenantScope {
        if (tenantId != null) {
            TenantId.requireValid(tenantId);
        }
    }

    /**
     * The scope of a request bound to one tenant.
     *
     * @param tenantId the tenant
     * @return the scope
     * @throws IllegalArgumentException if {@code tenantId} is null or not a valid tenant id
     */
    public static TenantScope of(String tenantId) {
        return new TenantScope(TenantId.requireValid(tenantId));
    }

    /**
     * Whether a record owned by {@code recordTenantId} is visible to this request.
     *
     * @param recordTenantId the tenant of the record
     * @return {@code true} when this scope is unscoped or is that tenant
     */
    public boolean allows(String recordTenantId) {
        return tenantId == null || tenantId.equals(recordTenantId);
    }
}
