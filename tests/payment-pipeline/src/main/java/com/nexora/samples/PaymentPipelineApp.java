package com.nexora.samples;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexora.api.NexoraEngine;
import com.nexora.api.observability.NexoraObservability;
import com.nexora.core.capability.CapabilityContract;
import com.nexora.core.capability.CapabilityResult;
import com.nexora.core.plan.AddStepAmendment;
import com.nexora.core.plan.InputBinding;
import com.nexora.core.plan.Step;
import com.nexora.planner.model.StepDefinition;
import com.nexora.spi.Capability;
import com.nexora.spi.CapabilityDescriptor;
import com.nexora.spi.CapabilityProvider;
import com.nexora.spi.NexoraPlugin;
import com.nexora.spi.PluginContext;
import com.nexora.spi.PluginDescriptor;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Payment fraud detection pipeline sample.
 *
 * DAG:
 *
 *   validate_request ─────────────────────────────────────┐
 *                                                          ├──> process_payment ──┬──> send_confirmation
 *   enrich_user_data ─┐                                   │                      │
 *                     ├──> run_fraud_check ───────────────┘                      └──> update_ledger
 *   check_velocity ───┘
 *
 * run_fraud_check injects a flag_for_review step (plan amendment).
 * process_payment has a p99=300ms SLA with a fallback capability.
 *
 * Start the app, open http://localhost:9464/, and submit:
 *   Goal: process payment
 *   Context: {"requestId":"REQ-001","amount":450.00,"userId":"USR-99"}
 */
public class PaymentPipelineApp {

    static final int HTTP_PORT = 9464;
    static final int WS_PORT   = 9465;
    static final ObjectMapper JSON = new ObjectMapper();
    static final AtomicLong requestCounter = new AtomicLong(1);

    public static void main(String[] args) throws Exception {
        NexoraEngine engine = buildEngine();
        NexoraObservability obs = NexoraObservability.attach(engine, 50, 300);

        // WebSocket broadcaster
        SnapshotBroadcaster broadcaster = new SnapshotBroadcaster(WS_PORT);
        obs.addSnapshotListener(snap -> {
            try { broadcaster.broadcastSnapshot(JSON.writeValueAsString(snap)); }
            catch (Exception ignored) {}
        });
        broadcaster.start();

        // HTTP server
        HttpServer http = HttpServer.create(new InetSocketAddress(HTTP_PORT), 0);
        http.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

        byte[] indexHtml = loadHtml();

        http.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (!"/".equals(path) && !"/index.html".equals(path)) {
                sendText(exchange, 404, "Not Found");
                return;
            }
            send(exchange, 200, "text/html; charset=utf-8", indexHtml);
        });

        http.createContext("/metrics", exchange -> {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) { sendText(exchange, 405, "Method Not Allowed"); return; }
            send(exchange, 200, obs.metricsContentType(),
                    obs.scrapePrometheus().getBytes(StandardCharsets.UTF_8));
        });

        http.createContext("/api/process", exchange -> {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) { sendText(exchange, 405, "Method Not Allowed"); return; }
            send(exchange, 200, "application/json; charset=utf-8",
                    JSON.writeValueAsBytes(obs.processSnapshot()));
        });

        http.createContext("/api/execute", exchange -> {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) { sendText(exchange, 405, "Method Not Allowed"); return; }
            ExecuteRequest req;
            try (InputStream body = exchange.getRequestBody()) {
                req = JSON.readValue(body, ExecuteRequest.class);
            } catch (Exception e) {
                sendJson(exchange, 400, Map.of("accepted", false, "error", "Invalid JSON: " + e.getMessage()));
                return;
            }
            if (req.goal() == null || req.goal().isBlank()) {
                sendJson(exchange, 400, Map.of("accepted", false, "error", "'goal' is required"));
                return;
            }
            Map<String, Object> ctx = req.context() == null ? Map.of() : req.context();
            engine.execute(req.goal(), ctx)
                    .whenComplete((r, ex) -> { if (ex != null) System.err.println("Execution error: " + ex.getMessage()); });
            sendJson(exchange, 202, Map.of("accepted", true, "goal", req.goal()));
        });

        CountDownLatch shutdown = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { http.stop(0); broadcaster.stop(500); obs.close(); }
            catch (Exception ignored) {}
            finally { shutdown.countDown(); }
        }));

        http.start();

        System.out.println();
        System.out.println("Payment Pipeline Sample");
        System.out.println("=======================");
        System.out.printf("UI:        http://localhost:%d/%n", HTTP_PORT);
        System.out.printf("Metrics:   http://localhost:%d/metrics%n", HTTP_PORT);
        System.out.printf("WebSocket: ws://localhost:%d/%n", WS_PORT);
        System.out.println();
        System.out.println("DAG:");
        System.out.println("  validate_request ────────────────────────────────────┐");
        System.out.println("                                                         ├──> process_payment ──┬──> send_confirmation");
        System.out.println("  enrich_user_data ─┐                                  │                       │");
        System.out.println("                    ├──> run_fraud_check ───────────────┘                       └──> update_ledger");
        System.out.println("  check_velocity ───┘");
        System.out.println();
        System.out.println("Firing initial execution...");

        // Fire a sample execution immediately so the UI has something to show on first load
        engine.execute("process payment",
                Map.of("requestId", "REQ-" + requestCounter.getAndIncrement(),
                       "amount", 450.00,
                       "userId", "USR-99"));

        System.out.println("Ready. Open http://localhost:9464/ in your browser.");
        System.out.println("Submit more executions via the form. Press Ctrl+C to stop.");
        System.out.println();

        shutdown.await();
    }

    // Engine wiring

    static NexoraEngine buildEngine() {
        return NexoraEngine.builder()
                .withPlugin(buildPlugin())
                // starts immediately (no deps)
                .withStepDefinition(new StepDefinition(
                        "validate_request", "validate_request",
                        g -> g.contains("process"),
                        Map.of("requestId", InputBinding.fromContext("intent.context.requestId")),
                        "validation", Set.of(), null, null))
                // starts immediately (no deps, parallel with validate_request)
                .withStepDefinition(new StepDefinition(
                        "enrich_user_data", "enrich_user_data",
                        g -> g.contains("process"),
                        Map.of("userId", InputBinding.fromContext("intent.context.userId")),
                        "userProfile", Set.of(), null, null))
                // starts immediately (no deps, parallel with above)
                .withStepDefinition(new StepDefinition(
                        "check_velocity", "check_velocity",
                        g -> g.contains("payment"),
                        Map.of("userId", InputBinding.fromContext("intent.context.userId")),
                        "velocityOk", Set.of(), null, null))
                // waits for enrich + velocity before scoring
                .withStepDefinition(new StepDefinition(
                        "run_fraud_check", "run_fraud_check",
                        g -> g.contains("payment"),
                        Map.of("amount", InputBinding.fromContext("intent.context.amount")),
                        "riskScore", Set.of("enrich_user_data", "check_velocity"), null, null))
                // waits for validate + fraud check; has SLA + fallback
                .withStepDefinition(new StepDefinition(
                        "process_payment", "process_payment",
                        g -> g.contains("payment"),
                        Map.of("requestId", InputBinding.fromContext("intent.context.requestId"),
                               "amount",    InputBinding.fromContext("intent.context.amount")),
                        "paymentId", Set.of("validate_request", "run_fraud_check"), null, null))
                // waits for payment (parallel with update_ledger)
                .withStepDefinition(new StepDefinition(
                        "send_confirmation", "send_confirmation",
                        g -> g.contains("payment"),
                        Map.of("paymentId", InputBinding.fromStep("process_payment", "paymentId")),
                        null, Set.of("process_payment"), null, null))
                // waits for payment (parallel with send_confirmation)
                .withStepDefinition(new StepDefinition(
                        "update_ledger", "update_ledger",
                        g -> g.contains("process"),
                        Map.of("paymentId", InputBinding.fromStep("process_payment", "paymentId")),
                        null, Set.of("process_payment"), null, null))
                .build();
    }

    static NexoraPlugin buildPlugin() {
        return new NexoraPlugin() {
            @Override public PluginDescriptor descriptor() {
                return new PluginDescriptor("payment-plugin", "1.0.0", "Payment fraud detection capabilities", List.of(), null);
            }
            @Override public void initialize(PluginContext ctx) {}
            @Override public void shutdown() {}

            @Override public List<CapabilityProvider> capabilityProviders() {
                return List.of(
                    cap("validate_request", CapabilityContract.none(), req -> {
                        sleep(15);
                        String id = String.valueOf(req.inputs().get("requestId"));
                        System.out.println("  [validate_request] " + id + " OK");
                        return CapabilityResult.success(Map.of("valid", true, "requestId", id));
                    }),

                    cap("enrich_user_data", CapabilityContract.none(), req -> {
                        sleep(60);
                        String userId = String.valueOf(req.inputs().get("userId"));
                        System.out.println("  [enrich_user_data] profile loaded for " + userId);
                        return CapabilityResult.success(Map.of(
                                "userId",   userId,
                                "tier",     "premium",
                                "country",  "US",
                                "history",  "clean"));
                    }),

                    cap("check_velocity", CapabilityContract.none(), req -> {
                        sleep(35);
                        System.out.println("  [check_velocity] velocity within limits");
                        return CapabilityResult.success(Map.of("velocityOk", true, "txLast24h", 3));
                    }),

                    // injects flag_for_review as a plan amendment
                    cap("run_fraud_check", CapabilityContract.none(), req -> {
                        sleep(90);
                        double amount = ((Number) req.inputs().getOrDefault("amount", 0)).doubleValue();
                        double risk = amount > 1000 ? 0.85 : 0.12;
                        System.out.printf("  [run_fraud_check] amount=%.2f risk=%.2f%n", amount, risk);

                        Step flagStep = new Step(
                                "flag_for_review", "flag_for_review",
                                Map.of("risk", InputBinding.literal(risk)),
                                null,
                                Set.of("run_fraud_check"),
                                null, null, null);

                        return CapabilityResult.success(
                                Map.of("riskScore", risk, "decision", risk > 0.5 ? "REVIEW" : "PASS"),
                                List.of(new AddStepAmendment(flagStep))
                        );
                    }),

                    // p99=300ms SLA, fallback to process_payment_fallback
                    cap("process_payment",
                        CapabilityContract.builder()
                            .p99Latency(Duration.ofMillis(300))
                            .maxErrorRate(0.05)
                            .fallback("process_payment_fallback")
                            .build(),
                        req -> {
                            sleep(80);
                            String pid = "PAY-" + System.currentTimeMillis();
                            System.out.println("  [process_payment] charged -> " + pid);
                            return CapabilityResult.success(Map.of("paymentId", pid, "status", "CAPTURED"));
                        }),

                    cap("process_payment_fallback", CapabilityContract.none(), req -> {
                        sleep(40);
                        String pid = "PAY-FALLBACK-" + System.currentTimeMillis();
                        System.out.println("  [process_payment_fallback] fallback charged -> " + pid);
                        return CapabilityResult.success(Map.of("paymentId", pid, "status", "CAPTURED_FALLBACK"));
                    }),

                    cap("send_confirmation", CapabilityContract.none(), req -> {
                        sleep(25);
                        System.out.println("  [send_confirmation] email sent for " + req.inputs().get("paymentId"));
                        return CapabilityResult.success(Map.of("channel", "email", "delivered", true));
                    }),

                    cap("update_ledger", CapabilityContract.none(), req -> {
                        sleep(30);
                        System.out.println("  [update_ledger] ledger updated for " + req.inputs().get("paymentId"));
                        return CapabilityResult.success(Map.of("ledgerEntry", "recorded"));
                    }),

                    cap("flag_for_review", CapabilityContract.none(), req -> {
                        sleep(20);
                        double risk = ((Number) req.inputs().getOrDefault("risk", 0)).doubleValue();
                        System.out.printf("  [flag_for_review] risk=%.2f -> queued for manual review%n", risk);
                        return CapabilityResult.success(Map.of("flagged", true, "queue", "fraud-review"));
                    })
                );
            }
        };
    }

    // HTTP helpers

    static void sendJson(HttpExchange ex, int status, Object body) throws IOException {
        send(ex, status, "application/json; charset=utf-8", JSON.writeValueAsBytes(body));
    }

    static void sendText(HttpExchange ex, int status, String body) throws IOException {
        send(ex, status, "text/plain; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
    }

    static void send(HttpExchange ex, int status, String contentType, byte[] payload) throws IOException {
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, payload.length);
        try (var os = ex.getResponseBody()) { os.write(payload); }
        finally { ex.close(); }
    }

    static byte[] loadHtml() {
        try (InputStream is = PaymentPipelineApp.class.getClassLoader().getResourceAsStream("observe/index.html")) {
            if (is == null) return "<h1>Missing observe/index.html</h1>".getBytes(StandardCharsets.UTF_8);
            String html = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            html = html.replace("{{WS_PORT}}", String.valueOf(WS_PORT));
            return html.getBytes(StandardCharsets.UTF_8);
        } catch (IOException e) {
            return ("<h1>Failed to load UI: " + e.getMessage() + "</h1>").getBytes(StandardCharsets.UTF_8);
        }
    }

    static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    static CapabilityProvider cap(String id, CapabilityContract contract, Capability impl) {
        return new CapabilityProvider() {
            @Override public CapabilityDescriptor descriptor() {
                return new CapabilityDescriptor(id, id, List.of(), List.of(), true, false, contract);
            }
            @Override public Capability create(PluginContext ctx) { return impl; }
        };
    }

    record ExecuteRequest(String goal, Map<String, Object> context) {}

    // ── WebSocket broadcaster ─────────────────────────────────────────────────

    static final class SnapshotBroadcaster extends WebSocketServer {
        SnapshotBroadcaster(int port) {
            super(new InetSocketAddress("0.0.0.0", port));
            setReuseAddr(true);
        }
        @Override public void onOpen(WebSocket conn, ClientHandshake h) {}
        @Override public void onClose(WebSocket conn, int code, String reason, boolean remote) {}
        @Override public void onMessage(WebSocket conn, String message) {}
        @Override public void onError(WebSocket conn, Exception ex) {}
        @Override public void onStart() {}

        void broadcastSnapshot(String json) {
            Collection<WebSocket> conns = getConnections();
            if (conns.isEmpty()) return;
            for (WebSocket ws : conns) { if (ws.isOpen()) ws.send(json); }
        }
    }
}
