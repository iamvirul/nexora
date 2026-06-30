package com.nexora.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.nexora.api.NexoraEngine;
import com.nexora.api.observability.NexoraObservability;
import com.nexora.core.intent.Intent;
import com.nexora.persistence.MissedFirePolicy;
import com.nexora.persistence.ScheduleRecord;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

@Command(
        name = "observe",
        // description loaded from help/observe.help at startup via HelpLoader
        mixinStandardHelpOptions = true
)
public class ObserveCommand implements Callable<Integer> {

    private static final ObjectMapper JSON = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @ParentCommand
    private NexoraCli parent;

    @Option(names = {"--host"}, defaultValue = "0.0.0.0",
            description = "Host interface for observability server. Default: ${DEFAULT-VALUE}")
    private String host;

    @Option(names = {"--port"}, defaultValue = "9464",
            description = "Port for UI/API/metrics endpoints. Default: ${DEFAULT-VALUE}")
    private int port;

    @Option(names = {"--max-executions"}, defaultValue = "100",
            description = "Number of recent executions retained in UI memory. Default: ${DEFAULT-VALUE}")
    private int maxExecutions;

    @Option(names = {"--max-events"}, defaultValue = "200",
            description = "Max timeline events retained per execution. Default: ${DEFAULT-VALUE}")
    private int maxEvents;

    @Override
    public Integer call() throws Exception {
        int wsPort = port + 1;

        NexoraEngine engine = parent.engine();
        NexoraObservability observability = NexoraObservability.attach(engine, maxExecutions, maxEvents);
        byte[] indexHtml = loadUiHtml(wsPort);

        // WebSocket broadcast server
        SnapshotBroadcaster broadcaster = new SnapshotBroadcaster(host, wsPort);
        observability.addSnapshotListener(snap -> {
            try {
                String json = JSON.writeValueAsString(snap);
                broadcaster.broadcastSnapshot(json);
            } catch (Exception ignored) {
            }
        });
        broadcaster.start();

        // HTTP server
        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

        server.createContext("/metrics", exchange -> {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendText(exchange, 405, "Method Not Allowed");
                return;
            }
            send(exchange, 200, observability.metricsContentType(),
                    observability.scrapePrometheus().getBytes(StandardCharsets.UTF_8));
        });

        server.createContext("/api/process", exchange -> {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendText(exchange, 405, "Method Not Allowed");
                return;
            }
            byte[] payload = JSON.writeValueAsBytes(observability.processSnapshot());
            send(exchange, 200, "application/json; charset=utf-8", payload);
        });

        server.createContext("/api/execute", exchange -> {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendText(exchange, 405, "Method Not Allowed");
                return;
            }

            ExecuteRequest request;
            try (InputStream body = exchange.getRequestBody()) {
                request = JSON.readValue(body, ExecuteRequest.class);
            } catch (Exception e) {
                sendJson(exchange, 400, Map.of(
                        "accepted", false,
                        "error", "Invalid JSON request: " + e.getMessage()
                ));
                return;
            }

            if (request.goal() == null || request.goal().isBlank()) {
                sendJson(exchange, 400, Map.of(
                        "accepted", false,
                        "error", "Field 'goal' is required"
                ));
                return;
            }

            Map<String, Object> context = request.context() == null ? Map.of() : request.context();
            com.nexora.core.intent.Intent intent = new com.nexora.core.intent.Intent(
                    request.goal(), 
                    context, 
                    null, 
                    request.webhookUrl(), 
                    request.webhookEvents()
            );
            engine.execute(intent)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            System.err.printf("Execution failed goal=%s error=%s%n",
                                    request.goal(), ex.getMessage());
                        }
                    });

            sendJson(exchange, 202, Map.of(
                    "accepted", true,
                    "message", "Execution accepted",
                    "goal", request.goal()
            ));
        });

        server.createContext("/api/webhook-deliveries/", exchange -> {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                try { sendText(exchange, 405, "Method Not Allowed"); } catch(Exception ignored) {}
                return;
            }
            String path = exchange.getRequestURI().getPath();
            String prefix = "/api/webhook-deliveries/";
            if (path.length() <= prefix.length()) {
                try { sendText(exchange, 400, "Missing executionId"); } catch(Exception ignored) {}
                return;
            }
            String executionId = path.substring(prefix.length());
            com.nexora.persistence.ExecutionStore store = engine.getStore();
            if (store == null) {
                try { sendJson(exchange, 503, Map.of("error", "Persistence disabled")); } catch(Exception ignored) {}
                return;
            }
            try {
                java.util.List<com.nexora.persistence.WebhookDeliveryRecord> deliveries = store.getWebhookDeliveries(executionId);
                try { sendJson(exchange, 200, deliveries); } catch(Exception ignored) {}
            } catch (Exception e) {
                try { sendJson(exchange, 500, Map.of("error", "internal", "details", e.getMessage())); } catch(Exception ignored) {}
            }
        });

        server.createContext("/api/dead-letters/", exchange -> {
            com.nexora.persistence.ExecutionStore dlqStore = engine.getStore();
            if (dlqStore == null) {
                try { sendJson(exchange, 503, Map.of("error", "Persistence disabled")); } catch(Exception ignored) {}
                return;
            }
            String path = exchange.getRequestURI().getPath();
            String prefix = "/api/dead-letters/";
            String remainder = path.length() > prefix.length() ? path.substring(prefix.length()) : "";

            // POST /api/dead-letters/{id}/replay
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod()) && remainder.endsWith("/replay")) {
                String dlId = remainder.substring(0, remainder.length() - "/replay".length());
                var dlOpt = dlqStore.findDeadLetterById(dlId);
                if (dlOpt.isEmpty()) {
                    try { sendJson(exchange, 404, Map.of("error", "Dead letter not found")); } catch(Exception ignored) {}
                    return;
                }
                var dl = dlOpt.get();
                if (dl.reviewState() != com.nexora.persistence.DeadLetterReviewState.PENDING) {
                    try { sendJson(exchange, 409, Map.of("error", "Dead letter is not in PENDING state")); } catch(Exception ignored) {}
                    return;
                }
                com.nexora.core.intent.Intent replayIntent = new com.nexora.core.intent.Intent(dl.goal(), dl.context());
                engine.execute(replayIntent).whenComplete((r, ex) -> {
                    if (ex != null) System.err.printf("DLQ replay failed id=%s error=%s%n", dlId, ex.getMessage());
                });
                dlqStore.updateDeadLetterState(dlId, com.nexora.persistence.DeadLetterReviewState.REPLAYED, null);
                try { sendJson(exchange, 202, Map.of("accepted", true, "deadLetterId", dlId)); } catch(Exception ignored) {}
                return;
            }

            // POST /api/dead-letters/{id}/resolve
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod()) && remainder.endsWith("/resolve")) {
                String dlId = remainder.substring(0, remainder.length() - "/resolve".length());
                var dlOpt = dlqStore.findDeadLetterById(dlId);
                if (dlOpt.isEmpty()) {
                    try { sendJson(exchange, 404, Map.of("error", "Dead letter not found")); } catch(Exception ignored) {}
                    return;
                }
                String reason = null;
                try (java.io.InputStream body = exchange.getRequestBody()) {
                    byte[] bytes = body.readAllBytes();
                    if (bytes.length > 0) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> payload = JSON.readValue(bytes, Map.class);
                        Object r = payload.get("reason");
                        reason = r != null ? r.toString() : null;
                    }
                } catch (Exception ignored) {}
                dlqStore.updateDeadLetterState(dlId, com.nexora.persistence.DeadLetterReviewState.RESOLVED, reason);
                try { sendJson(exchange, 200, Map.of("resolved", true, "deadLetterId", dlId)); } catch(Exception ignored) {}
                return;
            }

            try { sendJson(exchange, 405, Map.of("error", "Method Not Allowed")); } catch(Exception ignored) {}
        });

        server.createContext("/api/dead-letters", exchange -> {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                try { sendText(exchange, 405, "Method Not Allowed"); } catch(Exception ignored) {}
                return;
            }
            com.nexora.persistence.ExecutionStore dlqStore = engine.getStore();
            if (dlqStore == null) {
                try { sendJson(exchange, 503, Map.of("error", "Persistence disabled")); } catch(Exception ignored) {}
                return;
            }
            String query = exchange.getRequestURI().getQuery();
            com.nexora.persistence.DeadLetterReviewState stateFilter =
                    com.nexora.persistence.DeadLetterReviewState.PENDING;
            int page = 0;
            int size = 20;
            if (query != null) {
                for (String param : query.split("&")) {
                    String[] kv = param.split("=", 2);
                    if (kv.length < 2) continue;
                    switch (kv[0]) {
                        case "state" -> {
                            if ("ALL".equalsIgnoreCase(kv[1])) {
                                stateFilter = null;
                            } else {
                                try {
                                    stateFilter = com.nexora.persistence.DeadLetterReviewState.valueOf(kv[1].toUpperCase());
                                } catch (IllegalArgumentException e) {
                                    try { sendJson(exchange, 400, Map.of("error",
                                            "Invalid state '" + kv[1] + "'. Allowed: PENDING, RESOLVED, REPLAYED, ALL")); }
                                    catch (Exception ignored) {}
                                    return;
                                }
                            }
                        }
                        case "page" -> { try { page = Integer.parseInt(kv[1]); } catch (NumberFormatException ignored) {} }
                        case "size" -> { try { size = Math.min(100, Integer.parseInt(kv[1])); } catch (NumberFormatException ignored) {} }
                    }
                }
            }
            java.util.List<com.nexora.persistence.DeadLetterRecord> items =
                    dlqStore.findDeadLetters(stateFilter, page * size, size);
            try { sendJson(exchange, 200, Map.of("items", items, "page", page, "size", size)); } catch(Exception ignored) {}
        });

        server.createContext("/api/schedules/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String id   = path.substring("/api/schedules/".length());
            if (id.isBlank()) { try { sendText(exchange, 400, "Missing schedule id"); } catch (Exception ignored) {} return; }
            if (!"DELETE".equalsIgnoreCase(exchange.getRequestMethod())) {
                try { sendText(exchange, 405, "Method Not Allowed"); } catch (Exception ignored) {}
                return;
            }
            try {
                engine.cancelSchedule(id);
                sendJson(exchange, 200, Map.of("cancelled", true, "id", id));
            } catch (IllegalStateException e) {
                try { sendJson(exchange, 503, Map.of("error", e.getMessage())); } catch (Exception ignored) {}
            } catch (Exception e) {
                try { sendJson(exchange, 500, Map.of("error", e.getMessage())); } catch (Exception ignored) {}
            }
        });

        server.createContext("/api/schedules", exchange -> {
            String method = exchange.getRequestMethod();
            if ("GET".equalsIgnoreCase(method)) {
                try {
                    java.util.List<ScheduleRecord> records = engine.listSchedules();
                    java.util.List<Map<String, Object>> payload = records.stream()
                            .map(r -> {
                                java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
                                m.put("id",               r.id());
                                m.put("cronExpression",   r.cronExpression());
                                m.put("goal",             r.goal());
                                m.put("missedFirePolicy", r.missedFirePolicy().name());
                                m.put("active",           r.active());
                                m.put("nextFireAt",       r.nextFireAt().toString());
                                m.put("lastFiredAt",      r.lastFiredAt() != null ? r.lastFiredAt().toString() : null);
                                return (Map<String, Object>) m;
                            })
                            .toList();
                    sendJson(exchange, 200, Map.of("schedules", payload));
                } catch (IllegalStateException e) {
                    try { sendJson(exchange, 503, Map.of("error", e.getMessage())); } catch (Exception ignored) {}
                } catch (Exception e) {
                    try { sendJson(exchange, 500, Map.of("error", e.getMessage())); } catch (Exception ignored) {}
                }
            } else if ("POST".equalsIgnoreCase(method)) {
                @SuppressWarnings("unchecked")
                Map<String, Object> body;
                try (InputStream in = exchange.getRequestBody()) {
                    body = JSON.readValue(in, Map.class);
                } catch (Exception e) {
                    try { sendJson(exchange, 400, Map.of("error", "Invalid JSON")); } catch (Exception ignored) {}
                    return;
                }
                String cron  = body.get("cronExpression") instanceof String s ? s : null;
                String goal  = body.get("goal")           instanceof String s ? s : null;
                if (cron == null || cron.isBlank() || goal == null || goal.isBlank()) {
                    try { sendJson(exchange, 400, Map.of("error", "cronExpression and goal are required")); } catch (Exception ignored) {}
                    return;
                }
                Object rawPolicy = body.get("missedFirePolicy");
                String policyStr = (rawPolicy != null ? rawPolicy.toString() : "FIRE_ONCE");
                MissedFirePolicy policy;
                try { policy = MissedFirePolicy.valueOf(policyStr.toUpperCase()); }
                catch (IllegalArgumentException e) {
                    try { sendJson(exchange, 400, Map.of("error", "Invalid missedFirePolicy: " + policyStr)); } catch (Exception ignored) {}
                    return;
                }
                try {
                    var handle = engine.schedule(cron, new Intent(goal, Map.of()), policy);
                    sendJson(exchange, 201, Map.of("id", handle.id(), "nextFireAt", handle.nextFireTime().toString()));
                } catch (IllegalStateException e) {
                    try { sendJson(exchange, 503, Map.of("error", e.getMessage())); } catch (Exception ignored) {}
                } catch (IllegalArgumentException e) {
                    try { sendJson(exchange, 400, Map.of("error", e.getMessage())); } catch (Exception ignored) {}
                } catch (Exception e) {
                    try { sendJson(exchange, 500, Map.of("error", e.getMessage())); } catch (Exception ignored) {}
                }
            } else {
                try { sendText(exchange, 405, "Method Not Allowed"); } catch (Exception ignored) {}
            }
        });

        server.createContext("/health/ready", exchange -> {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendText(exchange, 405, "Method Not Allowed");
                return;
            }
            java.util.List<NexoraEngine.HealthSnapshot> snapshots = engine.listCapabilities().stream()
                    .map(c -> NexoraEngine.HealthSnapshot.from(engine.capabilityHealth(c.id())))
                    .toList();
            
            boolean anyOpen = snapshots.stream().anyMatch(s -> s.state() == com.nexora.executor.CapabilityContractMonitor.CircuitState.OPEN || s.state() == com.nexora.executor.CapabilityContractMonitor.CircuitState.HALF_OPEN);
            
            sendJson(exchange, anyOpen ? 503 : 200, Map.of(
                    "ready", !anyOpen,
                    "capabilities", snapshots
            ));
        });

        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (!Objects.equals(path, "/") && !Objects.equals(path, "/index.html")) {
                sendText(exchange, 404, "Not Found");
                return;
            }
            send(exchange, 200, "text/html; charset=utf-8", indexHtml);
        });

        CountDownLatch shutdownLatch = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                server.stop(0);
                try { broadcaster.stop(500); } catch (InterruptedException ignored) {}
                observability.close();
            } finally {
                shutdownLatch.countDown();
            }
        }));

        server.start();
        System.out.println("Nexora Observability Server");
        System.out.println("===========================");
        System.out.printf("UI:        http://%s:%d/%n", hostForDisplay(host), port);
        System.out.printf("Metrics:   http://%s:%d/metrics%n", hostForDisplay(host), port);
        System.out.printf("Process:   http://%s:%d/api/process%n", hostForDisplay(host), port);
        System.out.printf("Health:    http://%s:%d/health/ready%n", hostForDisplay(host), port);
        System.out.printf("WebSocket: ws://%s:%d/%n", hostForDisplay(host), wsPort);
        System.out.println();
        System.out.println("Press Ctrl+C to stop.");

        shutdownLatch.await();
        return 0;
    }

    private static String hostForDisplay(String host) {
        return "0.0.0.0".equals(host) ? "localhost" : host;
    }

    private static void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] payload = JSON.writeValueAsBytes(body);
        send(exchange, status, "application/json; charset=utf-8", payload);
    }

    private static void sendText(HttpExchange exchange, int status, String body) throws IOException {
        send(exchange, status, "text/plain; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange exchange, int status, String contentType, byte[] payload) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, payload.length);
        try (var os = exchange.getResponseBody()) {
            os.write(payload);
        } finally {
            exchange.close();
        }
    }

    private static byte[] loadUiHtml(int wsPort) {
        String resource = "observe/index.html";
        try (InputStream is = ObserveCommand.class.getClassLoader().getResourceAsStream(resource)) {
            if (is == null) {
                return "<h1>Missing observe/index.html resource</h1>".getBytes(StandardCharsets.UTF_8);
            }
            String html = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            html = html.replace("{{WS_PORT}}", String.valueOf(wsPort));
            return html.getBytes(StandardCharsets.UTF_8);
        } catch (IOException e) {
            return ("<h1>Failed to load UI resource</h1><pre>" + e.getMessage() + "</pre>")
                    .getBytes(StandardCharsets.UTF_8);
        }
    }

    private record ExecuteRequest(
            String goal, 
            Map<String, Object> context, 
            String webhookUrl, 
            java.util.List<com.nexora.core.execution.ExecutionStatus> webhookEvents) {}

    private static final class SnapshotBroadcaster extends WebSocketServer {

        SnapshotBroadcaster(String host, int port) {
            super(new InetSocketAddress("0.0.0.0".equals(host) ? "0.0.0.0" : host, port));
            setReuseAddr(true);
        }

        @Override
        public void onOpen(WebSocket conn, ClientHandshake handshake) {}

        @Override
        public void onClose(WebSocket conn, int code, String reason, boolean remote) {}

        @Override
        public void onMessage(WebSocket conn, String message) {}

        @Override
        public void onError(WebSocket conn, Exception ex) {}

        @Override
        public void onStart() {}

        void broadcastSnapshot(String json) {
            Collection<WebSocket> connections = getConnections();
            if (connections.isEmpty()) return;
            for (WebSocket ws : connections) {
                if (ws.isOpen()) {
                    ws.send(json);
                }
            }
        }
    }
}
