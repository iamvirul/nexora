package com.nexora.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexora.api.NexoraEngine;
import com.nexora.api.auth.TenantResolver;
import com.nexora.api.observability.NexoraObservability;
import com.nexora.api.ratelimit.RateLimitConfig;
import com.nexora.api.ratelimit.RequestRateLimiter;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The observe server's API behind the per-client rate limits, over real HTTP. */
class RateLimitEndpointsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String LOOPBACK = "127.0.0.1";
    private static final int EXECUTE_RPM = 10;

    private final HttpClient client = HttpClient.newHttpClient();
    private NexoraEngine engine;
    private NexoraObservability observability;
    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
            observability.close();
            engine.close();
        }
    }

    @Test
    void eleventhExecuteWithinAMinuteGets429WithRetryAfter() throws Exception {
        start(new RateLimitConfig(true, 60, EXECUTE_RPM, Set.of()));
        for (int i = 0; i < EXECUTE_RPM; i++) {
            assertEquals(202, execute(true).statusCode(), "request " + (i + 1));
        }

        HttpResponse<String> eleventh = execute(true);
        JsonNode error = JSON.readTree(eleventh.body()).get("error");
        long retryAfter =
                Long.parseLong(eleventh.headers().firstValue("Retry-After").orElseThrow());

        assertEquals(429, eleventh.statusCode());
        assertEquals("RATE_LIMIT_EXCEEDED", error.get("code").asText());
        assertEquals(retryAfter, error.get("retryAfterSeconds").asLong());
        assertTrue(retryAfter >= 1 && retryAfter <= 60, "Retry-After within the window: " + retryAfter);
    }

    @Test
    void pathVariantsOfExecuteCountAgainstTheExecuteLimit() throws Exception {
        // The JDK server matches contexts by plain prefix, so these all reach the execute route.
        start(new RateLimitConfig(true, 60, EXECUTE_RPM, Set.of()));
        for (int i = 0; i < EXECUTE_RPM; i++) {
            execute(true);
        }

        for (String variant : List.of("/api/execute/", "/api/execute/x", "/api/executeX")) {
            assertEquals(429, post(variant).statusCode(), variant);
        }
    }

    @Test
    void pathVariantsOfExecuteStartNothing() throws Exception {
        start(RateLimitConfig.disabled());

        for (String variant : List.of("/api/execute/", "/api/execute/x", "/api/executeX")) {
            assertEquals(404, post(variant).statusCode(), variant);
        }
    }

    @Test
    void everyRefusalIsCountedInTheMetricsScrape() throws Exception {
        start(new RateLimitConfig(true, 60, EXECUTE_RPM, Set.of()));
        for (int i = 0; i < EXECUTE_RPM + 2; i++) {
            execute(true);
        }

        String metrics = get("/metrics").body();

        assertTrue(
                metrics.contains(
                        "nexora_rate_limit_violations_total{endpoint=\"/api/execute\",client_ip=\"127.0.0.1\",} 2.0"),
                metrics);
    }

    @Test
    void unauthenticatedRequestsGet401WithoutSpendingTokens() throws Exception {
        start(new RateLimitConfig(true, 60, EXECUTE_RPM, Set.of()));
        for (int i = 0; i < EXECUTE_RPM + 5; i++) {
            assertEquals(401, execute(false).statusCode());
        }

        for (int i = 0; i < EXECUTE_RPM; i++) {
            assertEquals(202, execute(true).statusCode(), "authenticated request " + (i + 1));
        }
    }

    @Test
    void globalLimitCoversTheOtherApiRoutes() throws Exception {
        start(new RateLimitConfig(true, 3, EXECUTE_RPM, Set.of()));
        for (int i = 0; i < 3; i++) {
            assertEquals(200, authorizedGet("/api/process").statusCode());
        }

        assertEquals(429, authorizedGet("/api/schedules").statusCode());
    }

    @Test
    void healthProbesAndMetricsAreNeverLimited() throws Exception {
        start(new RateLimitConfig(true, 1, 1, Set.of()));

        for (int i = 0; i < 20; i++) {
            assertEquals(200, get("/health/live").statusCode());
            assertEquals(200, get("/metrics").statusCode());
        }
    }

    @Test
    void disabledLimitsApplyNoLimitAtAll() throws Exception {
        start(RateLimitConfig.disabled());

        for (int i = 0; i < 3 * EXECUTE_RPM; i++) {
            assertEquals(202, execute(true).statusCode(), "request " + (i + 1));
        }
        assertFalse(get("/metrics").body().contains(RequestRateLimiter.VIOLATIONS_METRIC));
    }

    private void start(RateLimitConfig limits) throws Exception {
        engine = NexoraEngine.builder().build();
        observability = NexoraObservability.attach(engine);
        server = HttpServer.create(new InetSocketAddress(LOOPBACK, 0), 0);
        ObserveCommand.registerRoutes(
                server,
                engine,
                observability,
                new byte[0],
                TestAuth.auth(),
                TenantResolver.singleTenant(),
                RequestRateLimiter.create(limits, observability.registry()));
        server.start();
    }

    private HttpResponse<String> execute(boolean authenticated) throws Exception {
        HttpRequest.Builder request = (authenticated
                        ? TestAuth.authorized(uri("/api/execute"))
                        : HttpRequest.newBuilder(uri("/api/execute")))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"goal\":\"anything\"}"));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path) throws Exception {
        return client.send(
                TestAuth.authorized(uri(path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"goal\":\"anything\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> authorizedGet(String path) throws Exception {
        return client.send(TestAuth.authorized(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://" + LOOPBACK + ":" + server.getAddress().getPort() + path);
    }
}
