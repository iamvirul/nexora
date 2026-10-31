package com.nexora.cli;

import com.nexora.api.NexoraEngine;
import com.nexora.api.auth.ApiAuth;
import com.nexora.api.auth.TenantResolver;
import com.nexora.api.auth.TenantScope;
import com.nexora.api.observability.NexoraObservability;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import javax.net.ssl.SSLParameters;
import org.java_websocket.WebSocket;
import org.java_websocket.drafts.Draft;
import org.java_websocket.exceptions.InvalidDataException;
import org.java_websocket.framing.CloseFrame;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.handshake.ServerHandshakeBuilder;
import org.java_websocket.server.SSLParametersWebSocketServerFactory;
import org.java_websocket.server.WebSocketServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The observe HTTP server and its WebSocket snapshot stream, started and stopped together.
 *
 * <p>{@code nexora observe} runs it, and so do samples such as the login demo, so every caller gets
 * the same routes, dashboard and authentication rather than a copy of them.
 */
public final class ObserveServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ObserveServer.class);
    private static final int MAX_PORT = 65_535;
    private static final int WEBSOCKET_STOP_TIMEOUT_MS = 500;

    /**
     * @param webSocketPort the dashboard expects it at {@code httpPort + 1} unless told otherwise
     * @param tls           the certificate for HTTPS and WSS, or {@code null} to serve plain HTTP and WS
     */
    /**
     * @param multiTenant whether every request acts for one tenant (see {@link TenantResolver});
     *                    false serves every execution to every caller, as before tenants existed
     */
    public record Config(
            String host,
            int httpPort,
            int webSocketPort,
            int maxExecutions,
            int maxEvents,
            ObserveTls tls,
            boolean multiTenant) {

        public static final int DEFAULT_MAX_EXECUTIONS = 100;
        public static final int DEFAULT_MAX_EVENTS = 200;

        public Config {
            Objects.requireNonNull(host, "host");
            requirePort(httpPort, "httpPort");
            requirePort(webSocketPort, "webSocketPort");
            if (httpPort == webSocketPort) {
                throw new IllegalArgumentException("httpPort and webSocketPort must differ, both are " + httpPort);
            }
            if (maxExecutions < 1 || maxEvents < 1) {
                throw new IllegalArgumentException("maxExecutions and maxEvents must be positive");
            }
        }

        /** Plain HTTP and WS. */
        public Config(String host, int httpPort, int webSocketPort, int maxExecutions, int maxEvents) {
            this(host, httpPort, webSocketPort, maxExecutions, maxEvents, null, false);
        }

        /** HTTP on {@code httpPort}, the WebSocket on the next port, default retention. */
        public static Config of(String host, int httpPort) {
            return new Config(host, httpPort, httpPort + 1, DEFAULT_MAX_EXECUTIONS, DEFAULT_MAX_EVENTS);
        }

        /** The same settings, served over HTTPS and WSS with {@code tls}. */
        public Config withTls(ObserveTls tls) {
            return new Config(
                    host, httpPort, webSocketPort, maxExecutions, maxEvents, Objects.requireNonNull(tls), multiTenant);
        }

        public Config withMultiTenant(boolean enabled) {
            return new Config(host, httpPort, webSocketPort, maxExecutions, maxEvents, tls, enabled);
        }

        TenantResolver tenantResolver() {
            return multiTenant ? TenantResolver.multiTenant() : TenantResolver.singleTenant();
        }

        private static void requirePort(int port, String name) {
            // Port 0 is refused: the dashboard is told both ports before they are bound.
            if (port < 1 || port > MAX_PORT) {
                throw new IllegalArgumentException(name + " must be between 1 and " + MAX_PORT + ", got " + port);
            }
        }
    }

    private final HttpServer http;
    private final SnapshotBroadcaster broadcaster;
    private final NexoraObservability observability;
    private final Config config;

    private ObserveServer(
            HttpServer http, SnapshotBroadcaster broadcaster, NexoraObservability observability, Config config) {
        this.http = http;
        this.broadcaster = broadcaster;
        this.observability = observability;
        this.config = config;
    }

    /**
     * Binds both ports and starts serving. If anything fails part way, whatever was already started
     * is stopped again before the exception propagates.
     *
     * @throws IOException if the HTTP port cannot be bound
     */
    public static ObserveServer start(NexoraEngine engine, ApiAuth auth, Config config) throws IOException {
        Objects.requireNonNull(engine, "engine");
        Objects.requireNonNull(auth, "auth");
        Objects.requireNonNull(config, "config");
        NexoraObservability observability =
                NexoraObservability.attach(engine, config.maxExecutions(), config.maxEvents());
        SnapshotBroadcaster broadcaster = null;
        try {
            TenantResolver tenants = config.tenantResolver();
            byte[] indexHtml = loadUiHtml(config.webSocketPort(), auth.passwordLoginEnabled(), config.multiTenant());
            broadcaster =
                    new SnapshotBroadcaster(config.host(), config.webSocketPort(), auth, tenants, config.httpPort());
            if (config.tls() != null) {
                broadcaster.setWebSocketFactory(new SSLParametersWebSocketServerFactory(
                        config.tls().context(), config.tls().parameters()));
            }
            SnapshotBroadcaster stream = broadcaster;
            observability.addSnapshotListener(snap -> {
                try {
                    stream.broadcastSnapshot(snap);
                } catch (Exception e) {
                    log.warn("Could not broadcast process snapshot", e);
                }
            });
            broadcaster.start();

            HttpServer http = createHttpServer(config);
            http.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            ObserveCommand.registerRoutes(http, engine, observability, indexHtml, auth, tenants);
            http.start();
            return new ObserveServer(http, broadcaster, observability, config);
        } catch (IOException | RuntimeException e) {
            stopQuietly(broadcaster);
            observability.close();
            throw e;
        }
    }

    /**
     * HTTPS when the config carries a certificate. Plain HTTP is logged at WARN on every start, since
     * the API key, passwords and the session cookie would then cross the network readable by anyone.
     */
    private static HttpServer createHttpServer(Config config) throws IOException {
        InetSocketAddress address = new InetSocketAddress(config.host(), config.httpPort());
        if (config.tls() == null) {
            log.warn(
                    "Serving plain HTTP on {}:{}: API keys, passwords and session cookies cross the network"
                            + " unencrypted. Start with --tls-cert and --tls-key (or --keystore), or put a TLS proxy"
                            + " in front.",
                    config.host(),
                    config.httpPort());
            return HttpServer.create(address, 0);
        }
        config.tls().validityWarning(Instant.now()).ifPresent(log::warn);
        HttpsServer https = HttpsServer.create(address, 0);
        SSLParameters parameters = config.tls().parameters();
        https.setHttpsConfigurator(new HttpsConfigurator(config.tls().context()) {
            @Override
            public void configure(HttpsParameters params) {
                params.setSSLParameters(parameters);
            }
        });
        return https;
    }

    /** Whether the server speaks HTTPS and WSS. */
    public boolean tlsEnabled() {
        return config.tls() != null;
    }

    public int httpPort() {
        return http.getAddress().getPort();
    }

    public int webSocketPort() {
        return config.webSocketPort();
    }

    public boolean multiTenant() {
        return config.multiTenant();
    }

    /** Stops accepting requests, closes the stream, and detaches from the engine. The engine itself is the caller's. */
    @Override
    public void close() {
        http.stop(0);
        stopQuietly(broadcaster);
        observability.close();
    }

    private static void stopQuietly(SnapshotBroadcaster broadcaster) {
        if (broadcaster == null) {
            return;
        }
        try {
            broadcaster.stop(WEBSOCKET_STOP_TIMEOUT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static byte[] loadUiHtml(int wsPort, boolean passwordLogin, boolean multiTenant) {
        String resource = "observe/index.html";
        try (InputStream is = ObserveCommand.class.getClassLoader().getResourceAsStream(resource)) {
            if (is == null) {
                return "<h1>Missing observe/index.html resource</h1>".getBytes(StandardCharsets.UTF_8);
            }
            String html = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            html = html.replace("{{WS_PORT}}", String.valueOf(wsPort))
                    .replace("{{PASSWORD_LOGIN}}", String.valueOf(passwordLogin))
                    .replace("{{MULTI_TENANT}}", String.valueOf(multiTenant));
            return html.getBytes(StandardCharsets.UTF_8);
        } catch (IOException e) {
            return ("<h1>Failed to load UI resource</h1><pre>" + e.getMessage() + "</pre>")
                    .getBytes(StandardCharsets.UTF_8);
        }
    }

    /**
     * Streams process snapshots. Each connection is scoped to a tenant at the handshake, by the same
     * rules as HTTP requests, and only ever receives that tenant's executions.
     */
    static final class SnapshotBroadcaster extends WebSocketServer {

        private final ApiAuth auth;
        private final TenantResolver tenants;
        private final int dashboardPort;

        SnapshotBroadcaster(String host, int port, ApiAuth auth, int dashboardPort) {
            this(host, port, auth, TenantResolver.singleTenant(), dashboardPort);
        }

        SnapshotBroadcaster(String host, int port, ApiAuth auth, TenantResolver tenants, int dashboardPort) {
            super(new InetSocketAddress("0.0.0.0".equals(host) ? "0.0.0.0" : host, port));
            this.auth = Objects.requireNonNull(auth, "auth");
            this.tenants = Objects.requireNonNull(tenants, "tenants");
            this.dashboardPort = dashboardPort;
            setReuseAddr(true);
        }

        /** Snapshots carry goals and contexts, so the stream is held to the same credentials as /api. */
        @Override
        public ServerHandshakeBuilder onWebsocketHandshakeReceivedAsServer(
                WebSocket conn, Draft draft, ClientHandshake request) throws InvalidDataException {
            ServerHandshakeBuilder response = super.onWebsocketHandshakeReceivedAsServer(conn, draft, request);
            ApiAuth.Caller caller = auth.authenticateWebSocket(
                            request.getFieldValue("Authorization"),
                            request.getFieldValue("Cookie"),
                            request.getFieldValue("Origin"),
                            request.getFieldValue("Host"),
                            dashboardPort)
                    .orElseThrow(() -> new InvalidDataException(CloseFrame.POLICY_VALIDATION, "Unauthorized"));
            // getFieldValue answers "" for an absent header, which the resolver reads as none sent.
            switch (tenants.resolve(caller, request.getFieldValue(TenantResolver.HEADER))) {
                case TenantResolver.Resolution.Scoped scoped -> conn.setAttachment(scoped.scope());
                case TenantResolver.Resolution.Rejected rejected ->
                    throw new InvalidDataException(CloseFrame.POLICY_VALIDATION, rejected.code());
            }
            return response;
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

        void broadcastSnapshot(NexoraObservability.ProcessSnapshot snapshot) throws IOException {
            Collection<WebSocket> connections = getConnections();
            if (connections.isEmpty()) return;
            // One serialisation per tenant per broadcast, however many sockets each tenant has open.
            Map<TenantScope, String> payloads = new HashMap<>();
            for (WebSocket ws : connections) {
                if (!ws.isOpen() || !(ws.getAttachment() instanceof TenantScope scope)) {
                    continue;
                }
                String json = payloads.get(scope);
                if (json == null) {
                    NexoraObservability.ProcessSnapshot visible =
                            scope.tenantId() == null ? snapshot : snapshot.forTenant(scope.tenantId());
                    json = ObserveCommand.JSON.writeValueAsString(visible);
                    payloads.put(scope, json);
                }
                ws.send(json);
            }
        }
    }
}
