package com.nexora.cli;

import com.nexora.api.NexoraEngine;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Liveness, readiness, and summary health endpoints for the observe server.
 *
 * <p>Unauthenticated by design — kubelet probes and load balancers carry no credentials.
 * Once #30 lands, these paths must stay excluded from the auth middleware.
 */
final class HealthEndpoints {

    static final String LIVE_PATH = "/health/live";
    static final String READY_PATH = "/health/ready";
    static final String SUMMARY_PATH = "/health";

    private static final int STATUS_OK = 200;
    private static final int STATUS_NOT_FOUND = 404;
    private static final int STATUS_METHOD_NOT_ALLOWED = 405;
    private static final int STATUS_UNAVAILABLE = 503;

    private final NexoraEngine engine;
    private final String version;

    HealthEndpoints(NexoraEngine engine, String version) {
        this.engine = Objects.requireNonNull(engine, "engine must not be null");
        this.version = Objects.requireNonNull(version, "version must not be null");
    }

    void register(HttpServer server) {
        server.createContext(LIVE_PATH, getOnly(LIVE_PATH, this::live));
        server.createContext(READY_PATH, getOnly(READY_PATH, this::ready));
        server.createContext(SUMMARY_PATH, getOnly(SUMMARY_PATH, this::summary));
    }

    /** Process is up and serving HTTP. Deliberately checks nothing else so a slow DB never triggers a restart. */
    private void live(HttpExchange exchange) throws IOException {
        ObserveCommand.sendJson(exchange, STATUS_OK, Map.of("status", NexoraEngine.HealthStatus.UP));
    }

    private void ready(HttpExchange exchange) throws IOException {
        NexoraEngine.ReadinessReport report = engine.readiness();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", report.status());
        body.put("checks", report.checks());
        ObserveCommand.sendJson(exchange, statusCode(report), body);
    }

    /**
     * Readiness plus version and capability circuit states. Circuits are informational only
     * and never flip the status — see {@link NexoraEngine#readiness()} for why.
     */
    private void summary(HttpExchange exchange) throws IOException {
        NexoraEngine.ReadinessReport report = engine.readiness();
        List<NexoraEngine.HealthSnapshot> capabilities = engine.listCapabilities().stream()
                .map(c -> NexoraEngine.HealthSnapshot.from(engine.capabilityHealth(c.id())))
                .toList();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", report.status());
        body.put("version", version);
        body.put("checks", report.checks());
        body.put("capabilities", capabilities);
        ObserveCommand.sendJson(exchange, statusCode(report), body);
    }

    private static int statusCode(NexoraEngine.ReadinessReport report) {
        return report.ready() ? STATUS_OK : STATUS_UNAVAILABLE;
    }

    /**
     * HttpServer contexts are prefix matches, so "/health" would otherwise also answer
     * "/health/anything". Probes must hit the exact path.
     */
    private static HttpHandler getOnly(String path, HttpHandler handler) {
        return exchange -> {
            if (!path.equals(exchange.getRequestURI().getPath())) {
                ObserveCommand.sendText(exchange, STATUS_NOT_FOUND, "Not Found");
                return;
            }
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                ObserveCommand.sendText(exchange, STATUS_METHOD_NOT_ALLOWED, "Method Not Allowed");
                return;
            }
            handler.handle(exchange);
        };
    }
}
