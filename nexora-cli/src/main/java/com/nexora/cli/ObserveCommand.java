package com.nexora.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.nexora.api.ExecutionNotReplayableException;
import com.nexora.api.NexoraEngine;
import com.nexora.api.auth.ApiAuth;
import com.nexora.api.auth.ApiKeyStore;
import com.nexora.api.auth.PasswordHasher;
import com.nexora.api.auth.SessionEndpoints;
import com.nexora.api.auth.TenantResolver;
import com.nexora.api.auth.TenantScope;
import com.nexora.api.auth.UserStore;
import com.nexora.api.observability.NexoraObservability;
import com.nexora.api.ratelimit.RateLimitConfig;
import com.nexora.api.ratelimit.RequestRateLimiter;
import com.nexora.core.execution.ExecutionHandle;
import com.nexora.core.intent.Intent;
import com.nexora.persistence.MissedFirePolicy;
import com.nexora.persistence.ScheduleRecord;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.function.UnaryOperator;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

@Command(
        name = "observe",
        // description loaded from help/observe.help at startup via HelpLoader
        mixinStandardHelpOptions = true)
public class ObserveCommand implements Callable<Integer> {

    static final ObjectMapper JSON = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS);

    // Populated from the jar manifest (see nexora-cli's shade plugin config). Falls back to
    // "unknown" for dev runs off exploded classes, where no manifest is on the classpath.
    private static final String NEXORA_VERSION =
            Objects.requireNonNullElse(ObserveCommand.class.getPackage().getImplementationVersion(), "unknown");

    private static final int EXIT_USAGE = 2;

    /** Preferred over --keystore-password, which other users can read in the process list. */
    static final String KEYSTORE_PASSWORD_ENV = "NEXORA_KEYSTORE_PASSWORD";

    private static final String API_PREFIX = "/api/";

    @ParentCommand
    private NexoraCli parent;

    @Spec
    private CommandSpec spec;

    /** Environment lookup; tests replace it so a developer's own NEXORA_API_KEY cannot leak in. */
    UnaryOperator<String> environment = System::getenv;

    @Option(
            names = {"--host"},
            defaultValue = "0.0.0.0",
            description = "Host interface for observability server. Default: ${DEFAULT-VALUE}")
    private String host;

    @Option(
            names = {"--port"},
            defaultValue = "9464",
            description = "Port for UI/API/metrics endpoints. Default: ${DEFAULT-VALUE}")
    private int port;

    @Option(
            names = {"--api-key"},
            description = "API key required on /api/* (min 32 chars). Prefer the " + ApiKeyStore.ENV_VAR
                    + " environment variable: flags are visible to other users in the process list.")
    private String apiKey;

    @Option(
            names = {"--api-key-file"},
            defaultValue = "${sys:user.home}/.nexora/observe-api-key.sha256",
            description = "Where the SHA-256 digest of a generated key is kept. Default: ${DEFAULT-VALUE}")
    private Path apiKeyFile;

    @Option(
            names = {"--users-file"},
            defaultValue = "${sys:user.home}/.nexora/observe-users",
            description =
                    "Dashboard users for username/password login, managed with `nexora user`. Default: ${DEFAULT-VALUE}")
    private Path usersFile;

    @Option(
            names = {"--rotate-api-key"},
            description = "Generate a new API key, replacing the stored digest; the old key stops working.")
    private boolean rotateApiKey;

    @Option(
            names = {"--tls-cert"},
            description = "PEM certificate to serve HTTPS and WSS with, optionally followed by its chain"
                    + " (certbot's fullchain.pem). Needs --tls-key. Make one for local use with `nexora tls-gen`.")
    private Path tlsCert;

    @Option(
            names = {"--tls-key"},
            description = "Unencrypted PEM private key for --tls-cert (certbot's privkey.pem).")
    private Path tlsKey;

    @Option(
            names = {"--keystore"},
            description = "PKCS12 keystore holding the server's key and certificate chain, instead of"
                    + " --tls-cert and --tls-key. Its password comes from " + KEYSTORE_PASSWORD_ENV
                    + " or --keystore-password.")
    private Path keystore;

    @Option(
            names = {"--keystore-password"},
            description = "Password for --keystore. Prefer the " + KEYSTORE_PASSWORD_ENV
                    + " environment variable: flags are visible to other users in the process list.")
    private String keystorePassword;

    @Option(
            names = {"--multi-tenant"},
            description = "Scope every request to one tenant: users reach only their own tenant's executions,"
                    + " and the server API key must name one in the " + TenantResolver.HEADER + " header.")
    private boolean multiTenant;

    @Option(
            names = {"--max-executions"},
            defaultValue = "100",
            description = "Number of recent executions retained in UI memory. Default: ${DEFAULT-VALUE}")
    private int maxExecutions;

    @Option(
            names = {"--max-events"},
            defaultValue = "200",
            description = "Max timeline events retained per execution. Default: ${DEFAULT-VALUE}")
    private int maxEvents;

    @Override
    public Integer call() throws Exception {
        // Config before the key, like TLS below: a bad value must not cost a generated or rotated key.
        RateLimitConfig rateLimit;
        try {
            rateLimit = parent.config().rateLimitConfig();
        } catch (IllegalArgumentException e) {
            spec.commandLine()
                    .getErr()
                    .println("Error: invalid observe.rateLimit in the config file: " + e.getMessage());
            return EXIT_USAGE;
        }
        ApiKeyStore.Resolution key;
        UserStore users;
        ObserveTls tls;
        try {
            // TLS first: a bad certificate should not cost a freshly generated key.
            tls = resolveTls();
            key = resolveApiKey(environment.apply(ApiKeyStore.ENV_VAR));
            users = new UserStore(usersFile, new PasswordHasher());
        } catch (IllegalArgumentException | IllegalStateException e) {
            spec.commandLine().getErr().println("Error: " + e.getMessage());
            return EXIT_USAGE;
        }
        ApiAuth auth = new ApiAuth(key.digest(), users);
        NexoraEngine engine = parent.engine();
        ObserveServer.Config config = new ObserveServer.Config(host, port, port + 1, maxExecutions, maxEvents)
                .withMultiTenant(multiTenant)
                .withRateLimit(rateLimit);
        ObserveServer server = ObserveServer.start(engine, auth, tls == null ? config : config.withTls(tls));
        int wsPort = server.webSocketPort();
        String scheme = server.tlsEnabled() ? "https" : "http";
        String wsScheme = server.tlsEnabled() ? "wss" : "ws";

        CountDownLatch shutdownLatch = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                server.close();
            } finally {
                shutdownLatch.countDown();
            }
        }));

        System.out.println("Nexora Observability Server");
        System.out.println("===========================");
        String display = hostForDisplay(host);
        System.out.printf("UI:        %s://%s:%d/%n", scheme, display, port);
        System.out.printf("Metrics:   %s://%s:%d/metrics%n", scheme, display, port);
        System.out.printf("Process:   %s://%s:%d/api/process%n", scheme, display, port);
        System.out.printf(
                "Health:    %s://%s:%d/health (live: /health/live, ready: /health/ready)%n", scheme, display, port);
        System.out.printf("API docs:  %s://%s:%d/docs (OpenAPI: /openapi.yaml)%n", scheme, display, port);
        System.out.printf("WebSocket: %s://%s:%d/%n", wsScheme, display, wsPort);
        System.out.println();
        printTlsStatus(tls);
        printTenancyStatus(server.multiTenant());
        printRateLimitStatus(rateLimit);
        printApiKeyStatus(key);
        printUserStatus(users);
        System.out.println();
        System.out.println("Press Ctrl+C to stop.");

        shutdownLatch.await();
        return 0;
    }

    /**
     * Registers every HTTP route of the observe server. Kept separate from {@link #call()} so
     * {@code OpenApiSpecTest} can check that each registered route is documented in openapi.yaml.
     */
    static void registerRoutes(
            HttpServer server, NexoraEngine engine, NexoraObservability observability, byte[] indexHtml, ApiAuth auth) {
        registerRoutes(server, engine, observability, indexHtml, auth, TenantResolver.singleTenant());
    }

    /** A handler that runs once the request's tenant is resolved; a rejected tenant never reaches it. */
    @FunctionalInterface
    interface ScopedHandler {
        void handle(HttpExchange exchange, TenantScope scope) throws IOException;
    }

    /** Resolves the tenant first; must sit inside {@link ApiAuth#protect}, which identifies the caller. */
    static HttpHandler scoped(TenantResolver tenants, ScopedHandler handler) {
        return exchange -> {
            Optional<TenantScope> scope = tenants.scope(exchange);
            if (scope.isPresent()) {
                handler.handle(exchange, scope.get());
            }
        };
    }

    static void registerRoutes(
            HttpServer server,
            NexoraEngine engine,
            NexoraObservability observability,
            byte[] indexHtml,
            ApiAuth auth,
            TenantResolver tenants) {
        registerRoutes(
                server,
                engine,
                observability,
                indexHtml,
                auth,
                tenants,
                RequestRateLimiter.create(RateLimitConfig.disabled(), observability.registry()));
    }

    /**
     * @param limiter per-client limits on every authenticated {@code /api} route, applied after
     *                authentication so an unauthenticated request gets 401 without spending tokens.
     *                Health probes, metrics, the dashboard and the docs are never limited: a throttled
     *                liveness probe would get the server restarted.
     */
    static void registerRoutes(
            HttpServer server,
            NexoraEngine engine,
            NexoraObservability observability,
            byte[] indexHtml,
            ApiAuth auth,
            TenantResolver tenants,
            RequestRateLimiter limiter) {
        UnaryOperator<HttpHandler> guard = handler -> auth.protect(limiter.limit(handler));
        server.createContext("/metrics", exchange -> {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendText(exchange, 405, "Method Not Allowed");
                return;
            }
            send(
                    exchange,
                    200,
                    observability.metricsContentType(),
                    observability.scrapePrometheus().getBytes(StandardCharsets.UTF_8));
        });

        server.createContext("/api/process", guard.apply(scoped(tenants, (exchange, scope) -> {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendText(exchange, 405, "Method Not Allowed");
                return;
            }
            NexoraObservability.ProcessSnapshot snapshot = observability.processSnapshot();
            byte[] payload =
                    JSON.writeValueAsBytes(scope.tenantId() == null ? snapshot : snapshot.forTenant(scope.tenantId()));
            send(exchange, 200, "application/json; charset=utf-8", payload);
        })));

        server.createContext("/api/webhook-deliveries/", guard.apply(scoped(tenants, (exchange, scope) -> {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                try {
                    sendText(exchange, 405, "Method Not Allowed");
                } catch (Exception ignored) {
                }
                return;
            }
            String path = exchange.getRequestURI().getPath();
            String prefix = "/api/webhook-deliveries/";
            if (path.length() <= prefix.length()) {
                try {
                    sendText(exchange, 400, "Missing executionId");
                } catch (Exception ignored) {
                }
                return;
            }
            String executionId = path.substring(prefix.length());
            com.nexora.persistence.ExecutionStore store = engine.getStore();
            if (store == null) {
                try {
                    sendJson(exchange, 503, Map.of("error", "Persistence disabled"));
                } catch (Exception ignored) {
                }
                return;
            }
            try {
                if (scope.tenantId() != null
                        && store.findById(scope.tenantId(), executionId).isEmpty()) {
                    // Another tenant's execution answers exactly like an unknown one.
                    sendJson(exchange, 404, Map.of("error", "Execution not found"));
                    return;
                }
                java.util.List<com.nexora.persistence.WebhookDeliveryRecord> deliveries =
                        store.getWebhookDeliveries(executionId);
                try {
                    sendJson(exchange, 200, deliveries);
                } catch (Exception ignored) {
                }
            } catch (Exception e) {
                try {
                    sendJson(exchange, 500, Map.of("error", "internal", "details", e.getMessage()));
                } catch (Exception ignored) {
                }
            }
        })));

        server.createContext("/api/dead-letters/", guard.apply(scoped(tenants, (exchange, scope) -> {
            com.nexora.persistence.ExecutionStore dlqStore = engine.getStore();
            if (dlqStore == null) {
                try {
                    sendJson(exchange, 503, Map.of("error", "Persistence disabled"));
                } catch (Exception ignored) {
                }
                return;
            }
            String path = exchange.getRequestURI().getPath();
            String prefix = "/api/dead-letters/";
            String remainder = path.length() > prefix.length() ? path.substring(prefix.length()) : "";

            // POST /api/dead-letters/{id}/replay
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod()) && remainder.endsWith("/replay")) {
                String dlId = remainder.substring(0, remainder.length() - "/replay".length());
                var dlOpt = dlqStore.findDeadLetterById(dlId).filter(dl -> scope.allows(dl.tenantId()));
                if (dlOpt.isEmpty()) {
                    try {
                        sendJson(exchange, 404, Map.of("error", "Dead letter not found"));
                    } catch (Exception ignored) {
                    }
                    return;
                }
                var dl = dlOpt.get();
                if (dl.reviewState() != com.nexora.persistence.DeadLetterReviewState.PENDING) {
                    try {
                        sendJson(exchange, 409, Map.of("error", "Dead letter is not in PENDING state"));
                    } catch (Exception ignored) {
                    }
                    return;
                }
                ExecutionHandle replay;
                try {
                    replay = engine.replayDeadLetter(dl);
                } catch (ExecutionNotReplayableException e) {
                    try {
                        sendJson(
                                exchange,
                                409,
                                Map.of("error", "Original execution is " + e.state() + " and cannot be replayed yet"));
                    } catch (Exception ignored) {
                    }
                    return;
                }
                replay.result().whenComplete((r, ex) -> {
                    if (ex != null) System.err.printf("DLQ replay failed id=%s error=%s%n", dlId, ex.getMessage());
                });
                dlqStore.updateDeadLetterState(dlId, com.nexora.persistence.DeadLetterReviewState.REPLAYED, null);
                try {
                    sendJson(
                            exchange,
                            202,
                            Map.of("accepted", true, "deadLetterId", dlId, "executionId", replay.executionId()));
                } catch (Exception ignored) {
                }
                return;
            }

            // POST /api/dead-letters/{id}/resolve
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod()) && remainder.endsWith("/resolve")) {
                String dlId = remainder.substring(0, remainder.length() - "/resolve".length());
                var dlOpt = dlqStore.findDeadLetterById(dlId).filter(dl -> scope.allows(dl.tenantId()));
                if (dlOpt.isEmpty()) {
                    try {
                        sendJson(exchange, 404, Map.of("error", "Dead letter not found"));
                    } catch (Exception ignored) {
                    }
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
                } catch (Exception ignored) {
                }
                dlqStore.updateDeadLetterState(dlId, com.nexora.persistence.DeadLetterReviewState.RESOLVED, reason);
                try {
                    sendJson(exchange, 200, Map.of("resolved", true, "deadLetterId", dlId));
                } catch (Exception ignored) {
                }
                return;
            }

            try {
                sendJson(exchange, 405, Map.of("error", "Method Not Allowed"));
            } catch (Exception ignored) {
            }
        })));

        server.createContext("/api/dead-letters", guard.apply(scoped(tenants, (exchange, scope) -> {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                try {
                    sendText(exchange, 405, "Method Not Allowed");
                } catch (Exception ignored) {
                }
                return;
            }
            com.nexora.persistence.ExecutionStore dlqStore = engine.getStore();
            if (dlqStore == null) {
                try {
                    sendJson(exchange, 503, Map.of("error", "Persistence disabled"));
                } catch (Exception ignored) {
                }
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
                                    stateFilter =
                                            com.nexora.persistence.DeadLetterReviewState.valueOf(kv[1].toUpperCase());
                                } catch (IllegalArgumentException e) {
                                    try {
                                        sendJson(
                                                exchange,
                                                400,
                                                Map.of(
                                                        "error",
                                                        "Invalid state '" + kv[1]
                                                                + "'. Allowed: PENDING, RESOLVED, REPLAYED, ALL"));
                                    } catch (Exception ignored) {
                                    }
                                    return;
                                }
                            }
                        }
                        case "page" -> {
                            try {
                                page = Integer.parseInt(kv[1]);
                            } catch (NumberFormatException ignored) {
                            }
                        }
                        case "size" -> {
                            try {
                                size = Math.min(100, Integer.parseInt(kv[1]));
                            } catch (NumberFormatException ignored) {
                            }
                        }
                    }
                }
            }
            java.util.List<com.nexora.persistence.DeadLetterRecord> items =
                    dlqStore.findDeadLetters(scope.tenantId(), stateFilter, page * size, size);
            try {
                sendJson(exchange, 200, Map.of("items", items, "page", page, "size", size));
            } catch (Exception ignored) {
            }
        })));

        server.createContext("/api/schedules/", guard.apply(scoped(tenants, (exchange, scope) -> {
            String path = exchange.getRequestURI().getPath();
            String id = path.substring("/api/schedules/".length());
            if (id.isBlank()) {
                try {
                    sendText(exchange, 400, "Missing schedule id");
                } catch (Exception ignored) {
                }
                return;
            }
            if (!"DELETE".equalsIgnoreCase(exchange.getRequestMethod())) {
                try {
                    sendText(exchange, 405, "Method Not Allowed");
                } catch (Exception ignored) {
                }
                return;
            }
            try {
                if (scope.tenantId() == null) {
                    engine.cancelSchedule(id);
                } else if (!engine.cancelSchedule(scope.tenantId(), id)) {
                    sendJson(exchange, 404, Map.of("error", "Schedule not found"));
                    return;
                }
                sendJson(exchange, 200, Map.of("cancelled", true, "id", id));
            } catch (IllegalStateException e) {
                try {
                    sendJson(exchange, 503, Map.of("error", e.getMessage()));
                } catch (Exception ignored) {
                }
            } catch (Exception e) {
                try {
                    sendJson(exchange, 500, Map.of("error", e.getMessage()));
                } catch (Exception ignored) {
                }
            }
        })));

        server.createContext("/api/schedules", guard.apply(scoped(tenants, (exchange, scope) -> {
            String method = exchange.getRequestMethod();
            if ("GET".equalsIgnoreCase(method)) {
                try {
                    java.util.List<ScheduleRecord> records =
                            scope.tenantId() == null ? engine.listSchedules() : engine.listSchedules(scope.tenantId());
                    java.util.List<Map<String, Object>> payload = records.stream()
                            .map(r -> {
                                java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
                                m.put("id", r.id());
                                m.put("cronExpression", r.cronExpression());
                                m.put("goal", r.goal());
                                m.put("missedFirePolicy", r.missedFirePolicy().name());
                                m.put("active", r.active());
                                m.put("nextFireAt", r.nextFireAt().toString());
                                m.put(
                                        "lastFiredAt",
                                        r.lastFiredAt() != null
                                                ? r.lastFiredAt().toString()
                                                : null);
                                m.put("lastStatus", r.lastStatus().name());
                                m.put("tenantId", r.tenantId());
                                return (Map<String, Object>) m;
                            })
                            .toList();
                    sendJson(exchange, 200, Map.of("schedules", payload));
                } catch (IllegalStateException e) {
                    try {
                        sendJson(exchange, 503, Map.of("error", e.getMessage()));
                    } catch (Exception ignored) {
                    }
                } catch (Exception e) {
                    try {
                        sendJson(exchange, 500, Map.of("error", e.getMessage()));
                    } catch (Exception ignored) {
                    }
                }
            } else if ("POST".equalsIgnoreCase(method)) {
                @SuppressWarnings("unchecked")
                Map<String, Object> body;
                try (InputStream in = exchange.getRequestBody()) {
                    body = JSON.readValue(in, Map.class);
                } catch (Exception e) {
                    try {
                        sendJson(exchange, 400, Map.of("error", "Invalid JSON"));
                    } catch (Exception ignored) {
                    }
                    return;
                }
                String cron = body.get("cronExpression") instanceof String s ? s : null;
                String goal = body.get("goal") instanceof String s ? s : null;
                if (cron == null || cron.isBlank() || goal == null || goal.isBlank()) {
                    try {
                        sendJson(exchange, 400, Map.of("error", "cronExpression and goal are required"));
                    } catch (Exception ignored) {
                    }
                    return;
                }
                Object rawPolicy = body.get("missedFirePolicy");
                String policyStr = (rawPolicy != null ? rawPolicy.toString() : "FIRE_ONCE");
                MissedFirePolicy policy;
                try {
                    policy = MissedFirePolicy.valueOf(policyStr.toUpperCase());
                } catch (IllegalArgumentException e) {
                    try {
                        sendJson(exchange, 400, Map.of("error", "Invalid missedFirePolicy: " + policyStr));
                    } catch (Exception ignored) {
                    }
                    return;
                }
                try {
                    var handle = engine.schedule(
                            cron, new Intent(goal, Map.of(), null, null, null, scope.tenantId()), policy);
                    sendJson(
                            exchange,
                            201,
                            Map.of(
                                    "id",
                                    handle.id(),
                                    "nextFireAt",
                                    handle.nextFireTime().toString()));
                } catch (IllegalStateException e) {
                    try {
                        sendJson(exchange, 503, Map.of("error", e.getMessage()));
                    } catch (Exception ignored) {
                    }
                } catch (IllegalArgumentException e) {
                    try {
                        sendJson(exchange, 400, Map.of("error", e.getMessage()));
                    } catch (Exception ignored) {
                    }
                } catch (Exception e) {
                    try {
                        sendJson(exchange, 500, Map.of("error", e.getMessage()));
                    } catch (Exception ignored) {
                    }
                }
            } else {
                try {
                    sendText(exchange, 405, "Method Not Allowed");
                } catch (Exception ignored) {
                }
            }
        })));

        new HealthEndpoints(engine, NEXORA_VERSION).register(server);
        new ExecutionEndpoints(engine, tenants).register(server, guard);
        new SessionEndpoints(auth, tenants).register(server);
        ApiDocsEndpoints.load().register(server);

        // Unknown /api paths answer 401 before 404, so an unauthenticated caller cannot map the API.
        server.createContext(API_PREFIX, guard.apply(exchange -> sendText(exchange, 404, "Not Found")));

        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (!Objects.equals(path, "/") && !Objects.equals(path, "/index.html")) {
                sendText(exchange, 404, "Not Found");
                return;
            }
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "GET");
                sendText(exchange, 405, "Method Not Allowed");
                return;
            }
            send(exchange, 200, "text/html; charset=utf-8", indexHtml);
        });
    }

    /**
     * The certificate to serve, or {@code null} for plain HTTP.
     *
     * @throws IllegalArgumentException for an incomplete or conflicting set of TLS options, or files that cannot be used
     */
    private ObserveTls resolveTls() {
        boolean pem = tlsCert != null || tlsKey != null;
        if (pem && keystore != null) {
            throw new IllegalArgumentException("use either --tls-cert and --tls-key, or --keystore, not both");
        }
        if (pem) {
            if (tlsCert == null || tlsKey == null) {
                throw new IllegalArgumentException("--tls-cert and --tls-key must be given together");
            }
            return ObserveTls.fromPem(tlsCert, tlsKey);
        }
        if (keystore == null) {
            if (keystorePassword != null) {
                throw new IllegalArgumentException("--keystore-password needs --keystore");
            }
            return null;
        }
        String password = keystorePassword != null ? keystorePassword : environment.apply(KEYSTORE_PASSWORD_ENV);
        if (password == null) {
            throw new IllegalArgumentException(
                    "--keystore needs its password: set " + KEYSTORE_PASSWORD_ENV + " or pass --keystore-password");
        }
        char[] chars = password.toCharArray();
        try {
            return ObserveTls.fromPkcs12(keystore, chars);
        } finally {
            Arrays.fill(chars, '\0');
        }
    }

    private static void printTlsStatus(ObserveTls tls) {
        if (tls == null) {
            System.out.println(
                    "TLS:       off. Keys and passwords travel in cleartext; see --tls-cert or `nexora tls-gen`");
            return;
        }
        System.out.println(
                "TLS:       " + tls.certificate().getSubjectX500Principal().getName() + ", valid until "
                        + tls.certificate().getNotAfter().toInstant());
    }

    private static void printRateLimitStatus(RateLimitConfig limits) {
        if (!limits.enabled()) {
            System.out.println("Limits:    off (observe.rateLimit.enabled=false); rate limit at your proxy instead");
            return;
        }
        System.out.printf(
                "Limits:    %d requests/min per client, %d/min to POST /api/execute%s%n",
                limits.globalRpm(),
                limits.executeRpm(),
                limits.trustedProxies().isEmpty() ? "" : "; X-Forwarded-For trusted from " + limits.trustedProxies());
    }

    private static void printTenancyStatus(boolean multiTenant) {
        if (multiTenant) {
            System.out.println(
                    "Tenants:   multi-tenant. Users reach only their own tenant (`nexora user add --tenant`);"
                            + " the API key must send " + TenantResolver.HEADER);
        } else {
            System.out.println("Tenants:   single-tenant; every caller sees every execution (--multi-tenant to scope)");
        }
    }

    /** --rotate-api-key excludes a supplied key: rotating while another key is in force would be a no-op. */
    private ApiKeyStore.Resolution resolveApiKey(String envKey) {
        if (rotateApiKey) {
            if (apiKey != null || (envKey != null && !envKey.isBlank())) {
                throw new IllegalArgumentException("--rotate-api-key cannot be combined with --api-key or "
                        + ApiKeyStore.ENV_VAR + "; rotation replaces the stored key at " + apiKeyFile);
            }
            return ApiKeyStore.rotate(apiKeyFile);
        }
        return ApiKeyStore.resolve(apiKey, envKey, apiKeyFile);
    }

    private void printApiKeyStatus(ApiKeyStore.Resolution key) {
        System.out.println("Auth:      /api/* requires Authorization: Bearer <key>; the UI logs in with a"
                + " username and password, or the key");
        switch (key.source()) {
            case FLAG -> System.out.println("           Key: from --api-key");
            case ENVIRONMENT -> System.out.println("           Key: from " + ApiKeyStore.ENV_VAR);
            case FILE ->
                System.out.println(
                        "           Key: stored digest at " + apiKeyFile + " (lost it? restart with --rotate-api-key)");
            case GENERATED, ROTATED -> {
                String newKey = key.newKey().orElseThrow();
                String banner = "=".repeat(newKey.length() + 4);
                System.out.println();
                System.out.println(
                        key.source() == ApiKeyStore.Source.ROTATED
                                ? "New API key (the previous key no longer works):"
                                : "Generated API key. Copy it now, it will not be shown again:");
                System.out.println(banner);
                System.out.println("  " + newKey);
                System.out.println(banner);
                System.out.println("Only its SHA-256 digest was saved, to " + apiKeyFile);
                System.out.println(
                        "For CI or containers, set " + ApiKeyStore.ENV_VAR + " instead so no key is printed.");
            }
        }
    }

    private static void printUserStatus(UserStore users) {
        if (users.isEmpty()) {
            System.out.println("Users:     none yet; add a dashboard login with `nexora user add <username>`"
                    + " (the API key works meanwhile)");
        } else {
            System.out.println("Users:     " + users.usernames().size() + " dashboard user(s) in " + users.file()
                    + "; manage with `nexora user`");
        }
    }

    private static String hostForDisplay(String host) {
        return "0.0.0.0".equals(host) ? "localhost" : host;
    }

    static void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] payload = JSON.writeValueAsBytes(body);
        send(exchange, status, "application/json; charset=utf-8", payload);
    }

    static void sendText(HttpExchange exchange, int status, String body) throws IOException {
        send(exchange, status, "text/plain; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
    }

    static void send(HttpExchange exchange, int status, String contentType, byte[] payload) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, payload.length);
        try (var os = exchange.getResponseBody()) {
            os.write(payload);
        } finally {
            exchange.close();
        }
    }
}
