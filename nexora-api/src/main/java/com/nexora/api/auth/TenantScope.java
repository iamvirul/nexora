package com.nexora.api.auth;

import com.nexora.core.tenant.TenantId;

/**
 * The tenant one API request acts for, as decided by {@link TenantResolver}.
 *
 * @param tenantId the only tenant the request may read or start work for; {@code null} on a
 *                 single-tenant server, where nothing is filtered and new work goes to
 *                 {@link TenantId#DEFAULT}
 */
public record TenantScope(String tenantId) {

    public static final TenantScope UNSCOPED = new TenantScope(null);

    public TenantScope {
        if (tenantId != null) {
            TenantId.requireValid(tenantId);
        }
    }

    public static TenantScope of(String tenantId) {
        return new TenantScope(TenantId.requireValid(tenantId));
    }

    /** Whether a record owned by {@code recordTenantId} is visible to this request. */
    public boolean allows(String recordTenantId) {
        return tenantId == null || tenantId.equals(recordTenantId);
    }
}
