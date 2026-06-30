package com.nexora.samples;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.nexora.api.NexoraEngine;
import com.nexora.api.observability.NexoraObservability;
import com.nexora.core.capability.CapabilityContract;
import com.nexora.core.capability.CapabilityResult;
import com.nexora.core.intent.Intent;
import com.nexora.event.ScheduledExecutionFiredEvent;
import com.nexora.persistence.MissedFirePolicy;
import com.nexora.core.plan.AddStepAmendment;
import com.nexora.core.plan.InputBinding;
import com.nexora.core.plan.Step;
import com.nexora.event.ExecutionDeadLetteredEvent;
import com.nexora.persistence.DeadLetterReviewState;
import com.nexora.persistence.ExecutionStore;
import com.nexora.persistence.jdbc.JdbcExecutionStore;
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
 * 8-step payment fraud detection pipeline demo.
 *
 * Base DAG (8 steps):
 *
 *   validate_request ──────────────────────────────────────────────┐
 *                                                                    ├──> process_payment ──┬──> send_confirmation
 *   enrich_user_data ─┐                                            │                       │
 *                     ├──> run_fraud_check ─────────────────────────┘                       └──> update_ledger
 *   check_velocity ───┤
 *                     │
 *   screen_sanctions ─┘
 *
 * run_fraud_check injects flag_for_review as a plan amendment (high-risk path).
 * process_payment has a p99=300ms SLA with a declared fallback.
 * All compensate capabilities are registered for saga rollback.
 *
 * Startup fires 4 scenarios automatically:
 *   1. Happy path          — all 8 steps complete, low risk
 *   2. High risk           — flag_for_review injected, REVIEW decision
 *   3. Gateway failure     — process_payment fails, saga compensates upstream steps
 *   4. Sanctions hit       — screen_sanctions blocks early, partial compensation
 *
 * Manual execution via the form:
 *   Goal:    process payment
 *   Context: {"requestId":"REQ-X","amount":450.00,"userId":"USR-99"}
 *   Flags:   "forceFailure":true | "forceBlockedUser":true | "forceVelocityFail":true
 */
public class PaymentPipelineApp {

    static final int HTTP_PORT = 9464;
    static final int WS_PORT   = 9465;
    static final ObjectMapper JSON = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    static final AtomicLong requestCounter = new AtomicLong(1);
    static JdbcExecutionStore executionStore;

    public static void main(String[] args) throws Exception {
        executionStore = JdbcExecutionStore.h2InMemory();
        NexoraEngine engine = buildEngine();
        NexoraObservability obs = NexoraObservability.attach(engine, 50, 300);

        engine.subscribe(ExecutionDeadLetteredEvent.class, e ->
            System.out.printf("%n  >>> DEAD LETTER CREATED  executionId=%s  code=%s  dlId=%s%n",
                e.executionId(), e.failureCode(), e.deadLetterId()));

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

        http.createContext("/api/dead-letters/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String prefix = "/api/dead-letters/";
            String remainder = path.length() > prefix.length() ? path.substring(prefix.length()) : "";
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod()) && remainder.endsWith("/replay")) {
                String dlId = remainder.substring(0, remainder.length() - "/replay".length());
                var dlOpt = executionStore.findDeadLetterById(dlId);
                if (dlOpt.isEmpty()) { sendJson(exchange, 404, Map.of("error", "Not found")); return; }
                var dl = dlOpt.get();
                if (dl.reviewState() != DeadLetterReviewState.PENDING) {
                    sendJson(exchange, 409, Map.of("error", "Not in PENDING state")); return;
                }
                engine.execute(dl.goal(), dl.context());
                executionStore.updateDeadLetterState(dlId, DeadLetterReviewState.REPLAYED, null);
                sendJson(exchange, 202, Map.of("accepted", true, "deadLetterId", dlId));
                return;
            }
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod()) && remainder.endsWith("/resolve")) {
                String dlId = remainder.substring(0, remainder.length() - "/resolve".length());
                var dlOpt = executionStore.findDeadLetterById(dlId);
                if (dlOpt.isEmpty()) { sendJson(exchange, 404, Map.of("error", "Not found")); return; }
                String reason = null;
                try (var body = exchange.getRequestBody()) {
                    byte[] bytes = body.readAllBytes();
                    if (bytes.length > 0) {
                        @SuppressWarnings("unchecked")
                        var p = (java.util.Map<String,Object>) JSON.readValue(bytes, java.util.Map.class);
                        Object r = p.get("reason");
                        reason = r != null ? r.toString() : null;
                    }
                } catch (Exception ignored) {}
                executionStore.updateDeadLetterState(dlId, DeadLetterReviewState.RESOLVED, reason);
                sendJson(exchange, 200, Map.of("resolved", true, "deadLetterId", dlId));
                return;
            }
            sendJson(exchange, 405, Map.of("error", "Method Not Allowed"));
        });

        http.createContext("/api/dead-letters", exchange -> {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendText(exchange, 405, "Method Not Allowed"); return;
            }
            String query = exchange.getRequestURI().getQuery();
            DeadLetterReviewState stateFilter = DeadLetterReviewState.PENDING;
            int page = 0, size = 20;
            if (query != null) {
                for (String param : query.split("&")) {
                    String[] kv = param.split("=", 2);
                    if (kv.length < 2) continue;
                    switch (kv[0]) {
                        case "state" -> {
                            if ("ALL".equalsIgnoreCase(kv[1])) stateFilter = null;
                            else try { stateFilter = DeadLetterReviewState.valueOf(kv[1].toUpperCase()); }
                                 catch (IllegalArgumentException ignored) {}
                        }
                        case "page" -> { try { page = Integer.parseInt(kv[1]); } catch (NumberFormatException ignored) {} }
                        case "size" -> { try { size = Math.min(100, Integer.parseInt(kv[1])); } catch (NumberFormatException ignored) {} }
                    }
                }
            }
            var items = executionStore.findDeadLetters(stateFilter, page * size, size);
            sendJson(exchange, 200, Map.of("items", items, "page", page, "size", size));
        });

        http.createContext("/api/schedules", exchange -> {
            String method = exchange.getRequestMethod();
            if ("GET".equalsIgnoreCase(method)) {
                var schedules = engine.listSchedules().stream()
                        .map(r -> Map.of(
                                "id", r.id(),
                                "cronExpression", r.cronExpression(),
                                "goal", r.goal(),
                                "missedFirePolicy", r.missedFirePolicy().name(),
                                "active", r.active(),
                                "nextFireAt", r.nextFireAt().toString(),
                                "lastFiredAt", r.lastFiredAt() != null ? r.lastFiredAt().toString() : ""))
                        .toList();
                sendJson(exchange, 200, Map.of("schedules", schedules));
            } else if ("POST".equalsIgnoreCase(method)) {
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> body;
                try (var in = exchange.getRequestBody()) {
                    body = (java.util.Map<String, Object>) JSON.readValue(in, java.util.Map.class);
                } catch (Exception e) {
                    sendJson(exchange, 400, Map.of("error", "Invalid JSON"));
                    return;
                }
                String cron = body.get("cronExpression") instanceof String s ? s : null;
                String goal = body.get("goal") instanceof String s ? s : null;
                if (cron == null || goal == null) {
                    sendJson(exchange, 400, Map.of("error", "cronExpression and goal are required"));
                    return;
                }
                Object rawPolicy = body.get("missedFirePolicy");
                String policyStr = rawPolicy != null ? rawPolicy.toString() : "FIRE_ONCE";
                MissedFirePolicy policy;
                try { policy = MissedFirePolicy.valueOf(policyStr.toUpperCase()); }
                catch (IllegalArgumentException e) { sendJson(exchange, 400, Map.of("error", "Invalid missedFirePolicy")); return; }
                try {
                    var handle = engine.schedule(cron, new Intent(goal, Map.of()), policy);
                    sendJson(exchange, 201, Map.of("id", handle.id(), "nextFireAt", handle.nextFireTime().toString()));
                } catch (IllegalArgumentException e) {
                    sendJson(exchange, 400, Map.of("error", e.getMessage()));
                }
            } else {
                sendText(exchange, 405, "Method Not Allowed");
            }
        });

        http.createContext("/api/schedules/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String id = path.substring("/api/schedules/".length());
            if ("DELETE".equalsIgnoreCase(exchange.getRequestMethod())) {
                engine.cancelSchedule(id);
                sendJson(exchange, 200, Map.of("cancelled", true, "id", id));
            } else {
                sendText(exchange, 405, "Method Not Allowed");
            }
        });

        http.createContext("/api/mock-webhook", exchange -> {
            System.out.println();
            System.out.println("  >>> WEBHOOK CALLBACK RECEIVED!");
            System.out.println("  >>> Signature: " + exchange.getRequestHeaders().getFirst("nexora-signature"));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });

        CountDownLatch shutdown = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { http.stop(0); broadcaster.stop(500); obs.close(); executionStore.close(); }
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
        System.out.println("DAG (8 steps):");
        System.out.println("  validate_request ──────────────────────────────────────────────┐");
        System.out.println("                                                                    ├──> process_payment ──┬──> send_confirmation");
        System.out.println("  enrich_user_data ─┐                                             │                       │");
        System.out.println("                    ├──> run_fraud_check ─────────────────────────┘                       └──> update_ledger");
        System.out.println("  check_velocity ───┤");
        System.out.println("                    │");
        System.out.println("  screen_sanctions ─┘");
        System.out.println();
        System.out.printf("DLQ:       http://localhost:%d/api/dead-letters%n", HTTP_PORT);
        System.out.println();
        System.out.println("Scenarios:");
        System.out.println("  1  Happy path       amount=450,  low risk");
        System.out.println("  2  High risk         amount=1500, fraud review amendment");
        System.out.println("  3  Gateway failure   forceFailure=true, saga compensates + DLQ entry");
        System.out.println("  4  Sanctions hit     forceBlockedUser=true, early failure + partial compensation + DLQ entry");
        System.out.println("  5  Execution timeout deadline=100ms, watchdog cancels execution + saga compensates");
        System.out.println("  6  Circuit Breaker   trigger failures to open the circuit, then test fallback routing");
        System.out.println("  7  Webhook Callback  dispatch webhook on execution completion with HMAC signature");
        System.out.println("  8  DLQ Replay        replays a dead-lettered execution from the DLQ");
        System.out.println("  9  Cron Schedule     registers a recurring payment reconciliation job (every minute)");
        System.out.println();
        System.out.println("Firing test scenarios...");
        System.out.println();

        // Scenario 1 — happy path, low risk
        System.out.println("  [1/5] Happy path — amount=450, low risk");
        engine.execute("process payment",
                Map.of("requestId", "REQ-" + requestCounter.getAndIncrement(),
                       "amount", 450.00,
                       "userId", "USR-99"));
        sleep(200);

        // Scenario 2 — high risk, triggers flag_for_review amendment
        System.out.println("  [2/5] High risk — amount=1500, fraud review path");
        engine.execute("process payment",
                Map.of("requestId", "REQ-" + requestCounter.getAndIncrement(),
                       "amount", 1500.00,
                       "userId", "USR-42"));
        sleep(200);

        // Scenario 3 — payment gateway rejects, saga compensates completed steps
        System.out.println("  [3/5] Gateway failure — process_payment fails, saga compensates");
        engine.execute("process payment",
                Map.of("requestId", "REQ-" + requestCounter.getAndIncrement(),
                       "amount", 250.00,
                       "userId", "USR-77",
                       "forceFailure", true));
        sleep(200);

        // Scenario 4 — sanctions blocklist hit, screen_sanctions fails early, partial compensation
        System.out.println("  [4/5] Sanctions hit — forceBlockedUser=true, early failure + partial compensation");
        engine.execute("process payment",
                Map.of("requestId", "REQ-" + requestCounter.getAndIncrement(),
                       "amount", 200.00,
                       "userId", "USR-BLOCKED",
                       "forceBlockedUser", true));
        sleep(200);

        // Scenario 5 — execution timeout, cancels plan execution and triggers compensation
        System.out.println("  [5/5] Execution timeout — 100ms deadline, watchdog cancels execution and triggers saga compensation");
        engine.execute("process payment",
                Map.of("requestId", "REQ-" + requestCounter.getAndIncrement(),
                       "amount", 300.00,
                       "userId", "USR-TIMEOUT"),
                Duration.ofMillis(100));

        System.out.println();
        System.out.println("  [6/6] Circuit Breaker Demo — trip the breaker and observe fallback routing");
        for (int i = 0; i < 5; i++) {
            System.out.println("        Sending failing request " + (i + 1));
            engine.execute("process payment",
                    Map.of("requestId", "REQ-CB-" + i,
                           "amount", 100.00,
                           "userId", "USR-CB",
                           "forceFailure", true));
            sleep(150); // wait for execution
        }
        
        System.out.println("        Circuit should now be OPEN. Sending a normal request...");
        engine.execute("process payment",
                Map.of("requestId", "REQ-CB-NORMAL",
                       "amount", 100.00,
                       "userId", "USR-CB",
                       "forceFailure", false)); // Not forcing failure, but should still hit fallback!
        sleep(200);

        System.out.println();
        System.out.println("  [7/7] Webhook Callback Demo — triggering execution with a webhook URL");
        engine.execute(new com.nexora.core.intent.Intent(
                "process payment",
                Map.of("requestId", "REQ-WEBHOOK", "amount", 50.00, "userId", "USR-WH"),
                null,
                "http://localhost:" + HTTP_PORT + "/api/mock-webhook",
                List.of(com.nexora.core.execution.ExecutionStatus.COMPLETED, com.nexora.core.execution.ExecutionStatus.FAILED, com.nexora.core.execution.ExecutionStatus.TIMED_OUT)
        ));
        sleep(200);

        // Scenario 8 — DLQ replay: wait for dead letters from scenarios 3 & 4, then replay one
        System.out.println();
        System.out.println("  [8/8] DLQ Replay — replaying a permanently failed execution from the dead letter queue");
        // Demo-only: arbitrary delay to let async dead-letter writes land before querying.
        // Production should coordinate via event subscription or synchronous store writes instead.
        sleep(800);
        var pendingDls = executionStore.findDeadLetters(DeadLetterReviewState.PENDING, 0, 10);
        System.out.printf("  DLQ has %d pending dead letters.%n", pendingDls.size());
        if (!pendingDls.isEmpty()) {
            var dl = pendingDls.get(0);
            System.out.printf("  Replaying dead letter id=%s executionId=%s goal='%s'%n",
                    dl.id(), dl.executionId(), dl.goal());
            engine.execute(dl.goal(), dl.context());
            executionStore.updateDeadLetterState(dl.id(), DeadLetterReviewState.REPLAYED, null);
            System.out.println("  Dead letter marked REPLAYED.");
            if (pendingDls.size() > 1) {
                var dl2 = pendingDls.get(1);
                executionStore.updateDeadLetterState(dl2.id(), DeadLetterReviewState.RESOLVED, "False positive — investigated");
                System.out.println("  Second dead letter marked RESOLVED (false positive).");
            }
        } else {
            System.out.println("  No pending dead letters yet — try scenario 3 or 4 manually via the UI.");
        }
        sleep(200);

        // Scenario 9 — cron schedule: reconciliation job fires every minute
        System.out.println();
        System.out.println("  [9/9] Cron Schedule — registering a recurring payment reconciliation job");
        engine.subscribe(ScheduledExecutionFiredEvent.class, e ->
            System.out.printf("%n  >>> SCHEDULED EXECUTION FIRED  scheduleId=%s  executionId=%s%n",
                e.scheduleId(), e.executionId()));
        var scheduleHandle = engine.schedule(
                "* * * * *",
                new Intent("process payment", Map.of(
                        "requestId", "SCHED-RECONCILE",
                        "amount", 1.00,
                        "userId", "USR-RECONCILE")),
                MissedFirePolicy.FIRE_ONCE);
        System.out.printf("  Schedule registered id=%s cron='* * * * *' nextFire=%s%n",
                scheduleHandle.id(), scheduleHandle.nextFireTime());
        System.out.printf("  GET  http://localhost:8090/api/schedules         — list all schedules%n");
        System.out.printf("  DELETE http://localhost:8090/api/schedules/%s  — cancel it%n", scheduleHandle.id());
        System.out.printf("  (Scheduled executions will appear in the UI as they fire)%n");

        System.out.println();
        System.out.println("Ready. Open http://localhost:9464/ in your browser.");
        System.out.println("Submit more executions via the form. Press Ctrl+C to stop.");
        System.out.println();

        shutdown.await();
    }

    // Engine wiring

    static NexoraEngine buildEngine() {
        return NexoraEngine.builder()
                .withWebhookSecret("payment-pipeline-secret-key-123")
                .withExecutionStore(executionStore)
                .withPlugin(buildPlugin())
                .withSagaEnabled(true)
                // step 1 - starts immediately
                .withStepDefinition(new StepDefinition(
                        "validate_request", "validate_request",
                        g -> g.contains("process"),
                        Map.of("requestId", InputBinding.fromContext("intent.context.requestId")),
                        "validation", Set.of(), null, null,
                        "validate_request_compensate"))
                // step 2 - starts immediately, parallel with step 1
                .withStepDefinition(new StepDefinition(
                        "enrich_user_data", "enrich_user_data",
                        g -> g.contains("process"),
                        Map.of("userId", InputBinding.fromContext("intent.context.userId")),
                        "userProfile", Set.of(), null, null,
                        "enrich_user_data_compensate"))
                // step 3 - starts immediately, parallel with steps 1-2
                .withStepDefinition(new StepDefinition(
                        "check_velocity", "check_velocity",
                        g -> g.contains("payment"),
                        Map.of("userId",            InputBinding.fromContext("intent.context.userId"),
                               "forceVelocityFail", InputBinding.fromContext("intent.context.forceVelocityFail")),
                        "velocityOk", Set.of(), null, null,
                        "check_velocity_compensate"))
                // step 4 - starts immediately, parallel with steps 1-3
                .withStepDefinition(new StepDefinition(
                        "screen_sanctions", "screen_sanctions",
                        g -> g.contains("payment"),
                        Map.of("userId",             InputBinding.fromContext("intent.context.userId"),
                               "forceBlockedUser",   InputBinding.fromContext("intent.context.forceBlockedUser")),
                        "sanctionsOk", Set.of(), null, null,
                        "screen_sanctions_compensate"))
                // step 5 - waits for steps 2, 3, 4
                .withStepDefinition(new StepDefinition(
                        "run_fraud_check", "run_fraud_check",
                        g -> g.contains("payment"),
                        Map.of("amount",       InputBinding.fromContext("intent.context.amount"),
                               "forceFailure", InputBinding.fromContext("intent.context.forceFailure")),
                        "riskScore", Set.of("enrich_user_data", "check_velocity", "screen_sanctions"), null, null,
                        "run_fraud_check_compensate"))
                // step 6 - waits for steps 1, 5; has p99 SLA + fallback
                .withStepDefinition(new StepDefinition(
                        "process_payment", "process_payment",
                        g -> g.contains("payment"),
                        Map.of("requestId",    InputBinding.fromContext("intent.context.requestId"),
                               "amount",       InputBinding.fromContext("intent.context.amount"),
                               "forceFailure", InputBinding.fromContext("intent.context.forceFailure")),
                        "paymentId", Set.of("validate_request", "run_fraud_check"), null, null))
                // step 7 - waits for step 6, parallel with step 8
                .withStepDefinition(new StepDefinition(
                        "send_confirmation", "send_confirmation",
                        g -> g.contains("payment"),
                        Map.of("paymentId", InputBinding.fromStep("process_payment", "paymentId")),
                        null, Set.of("process_payment"), null, null))
                // step 8 - waits for step 6, parallel with step 7
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
                    // Happy-path capabilities

                    cap("validate_request", CapabilityContract.none(), req -> {
                        sleep(15);
                        String id = String.valueOf(req.inputs().get("requestId"));
                        if ("INVALID".equals(id)) {
                            System.out.println("  [validate_request] " + id + " REJECTED - malformed request");
                            return CapabilityResult.failure("VALIDATION_FAILED", "Request ID is invalid: " + id);
                        }
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

                    // fails when forceVelocityFail=true — simulates rate-limit breach
                    cap("check_velocity", CapabilityContract.none(), req -> {
                        sleep(35);
                        boolean fail = Boolean.TRUE.equals(req.inputs().get("forceVelocityFail"));
                        if (fail) {
                            System.out.println("  [check_velocity] EXCEEDED - too many transactions");
                            return CapabilityResult.failure("VELOCITY_EXCEEDED",
                                    "Transaction rate limit breached for user");
                        }
                        System.out.println("  [check_velocity] velocity within limits");
                        return CapabilityResult.success(Map.of("velocityOk", true, "txLast24h", 3));
                    }),

                    // fails when forceBlockedUser=true — simulates sanctions blocklist hit
                    cap("screen_sanctions", CapabilityContract.none(), req -> {
                        sleep(40);
                        boolean blocked = Boolean.TRUE.equals(req.inputs().get("forceBlockedUser"));
                        String userId = String.valueOf(req.inputs().get("userId"));
                        if (blocked) {
                            System.out.println("  [screen_sanctions] " + userId + " BLOCKED - sanctions hit");
                            return CapabilityResult.failure("SANCTIONS_HIT",
                                    "User is on the sanctions blocklist: " + userId);
                        }
                        System.out.println("  [screen_sanctions] " + userId + " cleared");
                        return CapabilityResult.success(Map.of("sanctionsOk", true, "userId", userId));
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
                                null, null, null, null);

                        // Note: Map.of() prohibits null values — do NOT include optional
                        // flags here; they are read directly from the request context.
                        return CapabilityResult.success(
                                Map.of("riskScore", risk,
                                       "decision", risk > 0.5 ? "REVIEW" : "PASS"),
                                List.of(new AddStepAmendment(flagStep))
                        );
                    }),

                    // p99=300ms SLA, fallback to process_payment_fallback
                    // fails when forceFailure=true — triggers saga compensation
                    cap("process_payment",
                        CapabilityContract.builder()
                            .p99Latency(Duration.ofMillis(300))
                            .maxErrorRate(0.05)
                            .openDuration(Duration.ofSeconds(10))
                            .probeInterval(Duration.ofSeconds(5))
                            .fallback("process_payment_fallback")
                            .build(),
                        req -> {
                            sleep(80);
                            boolean forceFailure = Boolean.TRUE.equals(req.inputs().get("forceFailure"));
                            if (forceFailure) {
                                System.out.println("  [process_payment] FAILED - payment gateway rejected");
                                return CapabilityResult.failure("GATEWAY_REJECTED",
                                        "Payment gateway returned a hard decline");
                            }
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
                    }),

                    // Compensate capabilities (saga rollback)

                    cap("validate_request_compensate", CapabilityContract.none(), req -> {
                        sleep(10);
                        System.out.println("  [validate_request_compensate] request record voided");
                        return CapabilityResult.success(Map.of("voided", true));
                    }),

                    cap("enrich_user_data_compensate", CapabilityContract.none(), req -> {
                        sleep(10);
                        System.out.println("  [enrich_user_data_compensate] cached profile invalidated");
                        return CapabilityResult.success(Map.of("invalidated", true));
                    }),

                    cap("check_velocity_compensate", CapabilityContract.none(), req -> {
                        sleep(10);
                        System.out.println("  [check_velocity_compensate] velocity counter decremented");
                        return CapabilityResult.success(Map.of("decremented", true));
                    }),

                    cap("run_fraud_check_compensate", CapabilityContract.none(), req -> {
                        sleep(10);
                        System.out.println("  [run_fraud_check_compensate] fraud record retracted");
                        return CapabilityResult.success(Map.of("retracted", true));
                    }),

                    cap("screen_sanctions_compensate", CapabilityContract.none(), req -> {
                        sleep(10);
                        System.out.println("  [screen_sanctions_compensate] sanctions flag cleared");
                        return CapabilityResult.success(Map.of("cleared", true));
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

    // WebSocket broadcaster

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
