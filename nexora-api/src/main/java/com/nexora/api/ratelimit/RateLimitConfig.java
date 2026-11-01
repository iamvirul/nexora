package com.nexora.api.ratelimit;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Per-client request limits for the observe server's API.
 *
 * @param enabled        false applies no limit at all, for deployments that rate limit at a proxy
 * @param globalRpm      requests per minute each client may make to any authenticated API route
 * @param executeRpm     requests per minute each client may make to {@code POST /api/execute}, on top
 *                       of the global limit
 * @param trustedProxies IP addresses of reverse proxies whose {@code X-Forwarded-For} names the real
 *                       client; requests from anywhere else are limited by their own address
 */
public record RateLimitConfig(boolean enabled, int globalRpm, int executeRpm, Set<String> trustedProxies) {

    public static final int DEFAULT_GLOBAL_RPM = 60;
    public static final int DEFAULT_EXECUTE_RPM = 10;

    /**
     * @throws IllegalArgumentException if a limit is below 1, or a trusted proxy is not an IP address
     */
    public RateLimitConfig {
        if (globalRpm < 1 || executeRpm < 1) {
            throw new IllegalArgumentException("Rate limits must be at least 1 request per minute, got globalRpm="
                    + globalRpm + " executeRpm=" + executeRpm);
        }
        Objects.requireNonNull(trustedProxies, "trustedProxies");
        // Normalised once here, so a lookup compares canonical forms ("::1" and "0:0:0:0:0:0:0:1" alike).
        Set<String> normalised = new LinkedHashSet<>();
        for (String proxy : trustedProxies) {
            normalised.add(ClientAddress.parseLiteral(proxy)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Trusted proxy must be an IP address, not a host name or range: " + proxy)));
        }
        trustedProxies = Set.copyOf(normalised);
    }

    public static RateLimitConfig defaults() {
        return new RateLimitConfig(true, DEFAULT_GLOBAL_RPM, DEFAULT_EXECUTE_RPM, Set.of());
    }

    public static RateLimitConfig disabled() {
        return new RateLimitConfig(false, DEFAULT_GLOBAL_RPM, DEFAULT_EXECUTE_RPM, Set.of());
    }
}
