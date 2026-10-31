package com.nexora.api.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexora.core.tenant.TenantId;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decides which tenant an API request acts for.
 *
 * <p>In single-tenant mode (the default) every request is unscoped and {@value #HEADER} is
 * ignored, so the server behaves exactly as it did before tenants existed.
 *
 * <p>In multi-tenant mode the tenant comes from the credential wherever the credential has one, and
 * the header can only confirm it, never change it; trusting a client-chosen header alone would let
 * any user read any tenant:
 *
 * <ul>
 *   <li>A user's key or session is bound to the user's tenant. A header naming another tenant is
 *       refused with {@code 403 TENANT_FORBIDDEN}.
 *   <li>The server's own API key may act for any tenant, so it must name one: a missing header is
 *       {@code 400 TENANT_REQUIRED}. A key-holder dashboard session is bound to the tenant chosen
 *       when it was opened.
 *   <li>A header that is not a valid tenant id is {@code 400 INVALID_TENANT_ID}.
 * </ul>
 */
public final class TenantResolver {

    public static final String HEADER = "X-Tenant-ID";

    static final int STATUS_BAD_REQUEST = 400;
    static final int STATUS_FORBIDDEN = 403;

    private static final Logger log = LoggerFactory.getLogger(TenantResolver.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final boolean multiTenant;

    private TenantResolver(boolean multiTenant) {
        this.multiTenant = multiTenant;
    }

    public static TenantResolver singleTenant() {
        return new TenantResolver(false);
    }

    public static TenantResolver multiTenant() {
        return new TenantResolver(true);
    }

    public boolean isMultiTenant() {
        return multiTenant;
    }

    /** The outcome of resolving a request's tenant. */
    public sealed interface Resolution {
        record Scoped(TenantScope scope) implements Resolution {}

        record Rejected(int status, String code, String message) implements Resolution {}
    }

    public Resolution resolve(ApiAuth.Caller caller, String header) {
        if (!multiTenant) {
            return new Resolution.Scoped(TenantScope.UNSCOPED);
        }
        String requested = header == null || header.isBlank() ? null : header.strip();
        if (requested != null && !TenantId.isValid(requested)) {
            return new Resolution.Rejected(
                    STATUS_BAD_REQUEST,
                    "INVALID_TENANT_ID",
                    HEADER + " must be 1 to " + TenantId.MAX_LENGTH
                            + " characters of a-z, 0-9, '-' or '_', starting with a letter or digit");
        }
        if (caller.tenantId() != null) {
            if (requested != null && !requested.equals(caller.tenantId())) {
                return new Resolution.Rejected(
                        STATUS_FORBIDDEN, "TENANT_FORBIDDEN", "This credential cannot act for the requested tenant");
            }
            return new Resolution.Scoped(TenantScope.of(caller.tenantId()));
        }
        if (requested == null) {
            return new Resolution.Rejected(
                    STATUS_BAD_REQUEST,
                    "TENANT_REQUIRED",
                    "This server is multi-tenant: send " + HEADER + " to name the tenant");
        }
        return new Resolution.Scoped(TenantScope.of(requested));
    }

    /**
     * Resolves the tenant of a request already let through by {@link ApiAuth#protect}. A rejected
     * request is answered here, and the result is empty: the handler must return without writing.
     */
    public Optional<TenantScope> scope(HttpExchange exchange) throws IOException {
        ApiAuth.Caller caller = ApiAuth.callerOf(exchange);
        return switch (resolve(caller, exchange.getRequestHeaders().getFirst(HEADER))) {
            case Resolution.Scoped scoped -> Optional.of(scoped.scope());
            case Resolution.Rejected rejected -> {
                log.warn(
                        "Rejected API request tenant code={} method={} path={} principal={} remote={}",
                        rejected.code(),
                        exchange.getRequestMethod(),
                        exchange.getRequestURI().getPath(),
                        caller.name(),
                        exchange.getRemoteAddress());
                Responses.send(
                        exchange,
                        rejected.status(),
                        Responses.JSON,
                        JSON.writeValueAsString(
                                Map.of("error", Map.of("code", rejected.code(), "message", rejected.message()))));
                yield Optional.empty();
            }
        };
    }
}
