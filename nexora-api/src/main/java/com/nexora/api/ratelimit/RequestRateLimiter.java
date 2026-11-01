package com.nexora.api.ratelimit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.TimeMeter;
import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.Counter;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Token-bucket limits per client for the observe server's authenticated API.
 *
 * <p>Each client (see {@link ClientAddress}) gets {@link RateLimitConfig#globalRpm()} requests a
 * minute across every route it wraps, and {@link RateLimitConfig#executeRpm()} a minute for
 * {@code POST /api/execute} on top. A request over either limit gets {@code 429} with
 * {@code Retry-After}, is logged at {@code WARN} and counted in {@value #VIOLATIONS_METRIC}.
 *
 * <p>Wrap handlers inside {@code ApiAuth.protect}, so an unauthenticated request is answered
 * {@code 401} before it can spend a client's tokens. A disabled limiter wraps nothing.
 *
 * <p>Buckets refill all at once each minute, so a client gets exactly the configured number of
 * requests per window. Memory is bounded: buckets idle for a full window are already full and are
 * dropped, and the number tracked is capped at {@value #MAX_TRACKED_CLIENTS} per limit.
 */
public final class RequestRateLimiter {

    public static final String VIOLATIONS_METRIC = "nexora_rate_limit_violations_total";
    public static final String EXECUTE_PATH = "/api/execute";

    static final int MAX_TRACKED_CLIENTS = 100_000;

    private static final Logger log = LoggerFactory.getLogger(RequestRateLimiter.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration WINDOW = Duration.ofMinutes(1);
    private static final int STATUS_TOO_MANY_REQUESTS = 429;
    private static final long NANOS_PER_SECOND = TimeUnit.SECONDS.toNanos(1);

    private final RateLimitConfig config;
    private final Counter violations; // null when disabled: a disabled limiter registers nothing
    private final Limit global;
    private final Limit execute;

    private RequestRateLimiter(RateLimitConfig config, CollectorRegistry registry, TimeMeter time) {
        this.config = Objects.requireNonNull(config, "config");
        if (config.enabled()) {
            this.violations = Counter.build()
                    .name(VIOLATIONS_METRIC)
                    .labelNames("endpoint", "client_ip")
                    .help("Requests refused with 429 by the per-client rate limits, by route and client IP.")
                    .register(Objects.requireNonNull(registry, "registry"));
        } else {
            this.violations = null;
        }
        this.global = new Limit("global", config.globalRpm(), time);
        this.execute = new Limit("execute", config.executeRpm(), time);
    }

    /** @param registry where {@value #VIOLATIONS_METRIC} is registered; unused when disabled */
    public static RequestRateLimiter create(RateLimitConfig config, CollectorRegistry registry) {
        return new RequestRateLimiter(config, registry, TimeMeter.SYSTEM_NANOTIME);
    }

    /** As {@link #create}, on a clock the caller controls; for tests. */
    static RequestRateLimiter create(RateLimitConfig config, CollectorRegistry registry, TimeMeter time) {
        return new RequestRateLimiter(config, registry, time);
    }

    public RateLimitConfig config() {
        return config;
    }

    /** Clients holding a global bucket right now; for tests of the memory bound. */
    int trackedClients() {
        return global.buckets.size();
    }

    /** {@code next} behind the limits; {@code next} itself when the limiter is disabled. */
    public HttpHandler limit(HttpHandler next) {
        Objects.requireNonNull(next, "next");
        if (!config.enabled()) {
            return next;
        }
        return exchange -> {
            String client = ClientAddress.resolve(
                    exchange.getRemoteAddress(),
                    exchange.getRequestHeaders().get(ClientAddress.FORWARDED_FOR),
                    config.trustedProxies());
            Decision decision = check(client, isExecute(exchange));
            if (decision.allowed()) {
                next.handle(exchange);
            } else {
                reject(exchange, client, decision);
            }
        };
    }

    /** The outcome for one request; {@code retryAfterSeconds} is 0 when allowed. */
    record Decision(boolean allowed, String limit, long retryAfterSeconds) {
        static final Decision ALLOWED = new Decision(true, null, 0);
    }

    /**
     * Spends a token from the execute bucket first, then the global one, so a refused execute never
     * costs the client a global token.
     */
    Decision check(String client, boolean isExecute) {
        if (isExecute) {
            Decision executeDecision = execute.tryConsume(client);
            if (!executeDecision.allowed()) {
                return executeDecision;
            }
        }
        return global.tryConsume(client);
    }

    /**
     * By the matched context, not the request path: the JDK server matches contexts by plain prefix,
     * so {@code /api/execute/} and {@code /api/executeX} reach the execute route too and must not
     * slip past its limit.
     */
    private static boolean isExecute(HttpExchange exchange) {
        return "POST".equalsIgnoreCase(exchange.getRequestMethod())
                && EXECUTE_PATH.equals(exchange.getHttpContext().getPath());
    }

    private void reject(HttpExchange exchange, String client, Decision decision) throws IOException {
        // The route's context path, not the full URI: ids in paths would make the label unbounded.
        String endpoint = exchange.getHttpContext().getPath();
        violations.labels(endpoint, client).inc();
        log.warn(
                "Rate limit exceeded clientIp={} method={} endpoint={} limit={} retryAfterSeconds={}",
                client,
                exchange.getRequestMethod().toUpperCase(Locale.ROOT),
                endpoint,
                decision.limit(),
                decision.retryAfterSeconds());
        byte[] body = errorBody(decision.retryAfterSeconds());
        exchange.getResponseHeaders().set("Retry-After", String.valueOf(decision.retryAfterSeconds()));
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(STATUS_TOO_MANY_REQUESTS, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        } finally {
            exchange.close();
        }
    }

    private static byte[] errorBody(long retryAfterSeconds) throws JsonProcessingException {
        // Ordered like every other error body: code, then message, then this one's extra field.
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", "RATE_LIMIT_EXCEEDED");
        error.put(
                "message",
                "Too many requests; retry after " + retryAfterSeconds + " second"
                        + (retryAfterSeconds == 1 ? "" : "s"));
        error.put("retryAfterSeconds", retryAfterSeconds);
        return JSON.writeValueAsString(Map.of("error", error)).getBytes(StandardCharsets.UTF_8);
    }

    /** One limit's buckets, one per client. */
    private static final class Limit {

        private final String name;
        private final int requestsPerWindow;
        private final TimeMeter time;
        private final ConcurrentHashMap<String, Tracked> buckets = new ConcurrentHashMap<>();
        private final AtomicLong lastSweepNanos;

        private record Tracked(Bucket bucket, AtomicLong lastUsedNanos) {}

        Limit(String name, int requestsPerWindow, TimeMeter time) {
            this.name = name;
            this.requestsPerWindow = requestsPerWindow;
            this.time = time;
            this.lastSweepNanos = new AtomicLong(time.currentTimeNanos());
        }

        Decision tryConsume(String client) {
            long now = time.currentTimeNanos();
            sweepIfDue(now);
            if (buckets.size() >= MAX_TRACKED_CLIENTS && !buckets.containsKey(client)) {
                makeRoom(now);
            }
            Tracked tracked = buckets.computeIfAbsent(client, key -> new Tracked(newBucket(), new AtomicLong(now)));
            tracked.lastUsedNanos().set(now);
            ConsumptionProbe probe = tracked.bucket().tryConsumeAndReturnRemaining(1);
            if (probe.isConsumed()) {
                return Decision.ALLOWED;
            }
            return new Decision(false, name, retryAfterSeconds(probe.getNanosToWaitForRefill()));
        }

        private Bucket newBucket() {
            return Bucket.builder()
                    .addLimit(limit -> limit.capacity(requestsPerWindow).refillIntervally(requestsPerWindow, WINDOW))
                    .withCustomTimePrecision(time)
                    .build();
        }

        /** Rounded up: a client that waits exactly this long always finds a token. */
        private static long retryAfterSeconds(long nanos) {
            return Math.max(1, (nanos + NANOS_PER_SECOND - 1) / NANOS_PER_SECOND);
        }

        /** A bucket unused for a full window has refilled completely, so dropping it changes nothing. */
        private void sweepIfDue(long now) {
            long last = lastSweepNanos.get();
            if (now - last < WINDOW.toNanos() || !lastSweepNanos.compareAndSet(last, now)) {
                return;
            }
            buckets.values().removeIf(t -> now - t.lastUsedNanos().get() >= WINDOW.toNanos());
        }

        /**
         * At the cap with every bucket still in use: a flood from that many addresses. Forgetting them
         * all gives each a fresh window, which is the price of bounded memory under such a flood.
         */
        private void makeRoom(long now) {
            buckets.values().removeIf(t -> now - t.lastUsedNanos().get() >= WINDOW.toNanos());
            if (buckets.size() >= MAX_TRACKED_CLIENTS) {
                log.warn(
                        "Rate limiter tracking {} clients for the {} limit, the maximum; resetting its buckets",
                        buckets.size(),
                        name);
                buckets.clear();
            }
        }
    }
}
