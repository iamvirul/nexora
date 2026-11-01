package com.nexora.api.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.github.bucket4j.TimeMeter;
import io.prometheus.client.CollectorRegistry;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class RequestRateLimiterTest {

    private static final String CLIENT = "203.0.113.7";
    private static final String OTHER_CLIENT = "203.0.113.8";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ManualTime time = new ManualTime();
    private final CollectorRegistry registry = new CollectorRegistry();
    private final RequestRateLimiter limiter = RequestRateLimiter.create(RateLimitConfig.defaults(), registry, time);

    @Test
    void eleventhExecuteWithinAMinuteIsRefused() {
        for (int i = 0; i < RateLimitConfig.DEFAULT_EXECUTE_RPM; i++) {
            assertTrue(limiter.check(CLIENT, true).allowed(), "request " + (i + 1));
        }

        RequestRateLimiter.Decision eleventh = limiter.check(CLIENT, true);

        assertFalse(eleventh.allowed());
        assertEquals("execute", eleventh.limit());
    }

    @Test
    void retryAfterIsTheTimeLeftUntilTheWindowRefills() {
        exhaustExecute(CLIENT);

        time.advanceSeconds(20);
        assertEquals(40, limiter.check(CLIENT, true).retryAfterSeconds());
        time.advanceMillis(39_500);
        assertEquals(1, limiter.check(CLIENT, true).retryAfterSeconds(), "rounded up, never 0");
        time.advanceMillis(500);
        assertTrue(limiter.check(CLIENT, true).allowed(), "waiting exactly Retry-After finds a token");
    }

    @Test
    void globalLimitCoversEveryRouteIncludingExecute() {
        for (int i = 0; i < RateLimitConfig.DEFAULT_GLOBAL_RPM; i++) {
            assertTrue(limiter.check(CLIENT, false).allowed(), "request " + (i + 1));
        }

        assertEquals("global", limiter.check(CLIENT, false).limit());
        assertEquals("global", limiter.check(CLIENT, true).limit());
    }

    @Test
    void refusedExecuteDoesNotSpendAGlobalToken() {
        exhaustExecute(CLIENT);
        for (int i = 0; i < 5; i++) {
            assertFalse(limiter.check(CLIENT, true).allowed());
        }

        int globalLeft = RateLimitConfig.DEFAULT_GLOBAL_RPM - RateLimitConfig.DEFAULT_EXECUTE_RPM;
        for (int i = 0; i < globalLeft; i++) {
            assertTrue(limiter.check(CLIENT, false).allowed(), "global request " + (i + 1));
        }
        assertFalse(limiter.check(CLIENT, false).allowed());
    }

    @Test
    void eachClientHasItsOwnBuckets() {
        exhaustExecute(CLIENT);

        assertFalse(limiter.check(CLIENT, true).allowed());
        assertTrue(limiter.check(OTHER_CLIENT, true).allowed());
    }

    @Test
    void bucketsIdleForAWholeWindowAreForgotten() {
        limiter.check("198.51.100.1", false);
        limiter.check("198.51.100.2", false);
        limiter.check("198.51.100.3", false);
        assertEquals(3, limiter.trackedClients());

        time.advanceSeconds(61);
        limiter.check(CLIENT, false);

        assertEquals(1, limiter.trackedClients());
    }

    @Test
    void disabledLimiterWrapsNothingAndRegistersNoMetric() {
        CollectorRegistry untouched = new CollectorRegistry();
        HttpHandler handler = exchange -> {};

        RequestRateLimiter disabled = RequestRateLimiter.create(RateLimitConfig.disabled(), untouched, time);

        assertSame(handler, disabled.limit(handler));
        assertFalse(untouched.metricFamilySamples().hasMoreElements());
    }

    @Test
    void limitsBelowOneAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new RateLimitConfig(true, 0, 10, Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new RateLimitConfig(true, 60, 0, Set.of()));
    }

    @Test
    void refusedRequestGets429WithRetryAfterIsCountedAndLoggedAtWarn() throws Exception {
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        Logger logger = (Logger) LoggerFactory.getLogger(RequestRateLimiter.class);
        logs.start();
        logger.addAppender(logs);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(RequestRateLimiter.EXECUTE_PATH, limiter.limit(exchange -> {
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
        }));
        server.start();
        try {
            HttpClient client = HttpClient.newHttpClient();
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + RequestRateLimiter.EXECUTE_PATH);
            HttpRequest post = HttpRequest.newBuilder(uri)
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            for (int i = 0; i < RateLimitConfig.DEFAULT_EXECUTE_RPM; i++) {
                assertEquals(
                        202,
                        client.send(post, HttpResponse.BodyHandlers.discarding())
                                .statusCode());
            }
            time.advanceSeconds(15);

            HttpResponse<String> refused = client.send(post, HttpResponse.BodyHandlers.ofString());
            JsonNode error = JSON.readTree(refused.body()).get("error");

            assertEquals(429, refused.statusCode());
            assertEquals("45", refused.headers().firstValue("Retry-After").orElseThrow());
            assertEquals("RATE_LIMIT_EXCEEDED", error.get("code").asText());
            assertEquals(45, error.get("retryAfterSeconds").asLong());
            assertEquals(
                    1.0,
                    registry.getSampleValue(
                            RequestRateLimiter.VIOLATIONS_METRIC,
                            new String[] {"endpoint", "client_ip"},
                            new String[] {RequestRateLimiter.EXECUTE_PATH, "127.0.0.1"}));
            ILoggingEvent warning = logs.list.stream()
                    .filter(e -> e.getLevel() == Level.WARN)
                    .findFirst()
                    .orElseThrow();
            assertTrue(warning.getFormattedMessage().contains("clientIp=127.0.0.1"), warning.getFormattedMessage());
            assertTrue(
                    warning.getFormattedMessage().contains("endpoint=" + RequestRateLimiter.EXECUTE_PATH),
                    warning.getFormattedMessage());
        } finally {
            server.stop(0);
            logger.detachAppender(logs);
        }
    }

    @Test
    void violationsCounterOnlyExistsOnceSomethingIsRefused() {
        assertNull(registry.getSampleValue(
                RequestRateLimiter.VIOLATIONS_METRIC,
                new String[] {"endpoint", "client_ip"},
                new String[] {RequestRateLimiter.EXECUTE_PATH, CLIENT}));
    }

    private void exhaustExecute(String client) {
        for (int i = 0; i < RateLimitConfig.DEFAULT_EXECUTE_RPM; i++) {
            limiter.check(client, true);
        }
    }

    /** A clock the test moves by hand, so refill timing is exact. */
    private static final class ManualTime implements TimeMeter {
        private final AtomicLong nanos = new AtomicLong(TimeUnit.HOURS.toNanos(1));

        @Override
        public long currentTimeNanos() {
            return nanos.get();
        }

        @Override
        public boolean isWallClockBased() {
            return false;
        }

        void advanceSeconds(long seconds) {
            nanos.addAndGet(TimeUnit.SECONDS.toNanos(seconds));
        }

        void advanceMillis(long millis) {
            nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(millis));
        }
    }
}
