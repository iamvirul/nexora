package com.nexora.cli;

import com.nexora.api.NexoraEngine;
import com.nexora.api.auth.ApiAuth;
import com.nexora.api.observability.NexoraObservability;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.Executors;
import org.java_websocket.WebSocket;
import org.java_websocket.drafts.Draft;
import org.java_websocket.exceptions.InvalidDataException;
import org.java_websocket.framing.CloseFrame;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.handshake.ServerHandshakeBuilder;
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
     */
    public record Config(String host, int httpPort, int webSocketPort, int maxExecutions, int maxEvents) {

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

        /** HTTP on {@code httpPort}, the WebSocket on the next port, default retention. */
        public static Config of(String host, int httpPort) {
            return new Config(host, httpPort, httpPort + 1, DEFAULT_MAX_EXECUTIONS, DEFAULT_MAX_EVENTS);
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
            byte[] indexHtml = loadUiHtml(config.webSocketPort(), auth.passwordLoginEnabled());
            broadcaster = new SnapshotBroadcaster(config.host(), config.webSocketPort(), auth, config.httpPort());
            SnapshotBroadcaster stream = broadcaster;
            observability.addSnapshotListener(snap -> {
                try {
                    stream.broadcastSnapshot(ObserveCommand.JSON.writeValueAsString(snap));
                } catch (Exception e) {
                    log.warn("Could not broadcast process snapshot", e);
                }
            });
            broadcaster.start();

            HttpServer http = HttpServer.create(new InetSocketAddress(config.host(), config.httpPort()), 0);
            http.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            ObserveCommand.registerRoutes(http, engine, observability, indexHtml, auth);
            http.start();
            return new ObserveServer(http, broadcaster, observability, config);
        } catch (IOException | RuntimeException e) {
            stopQuietly(broadcaster);
            observability.close();
            throw e;
        }
    }

    public int httpPort() {
        return http.getAddress().getPort();
    }

    public int webSocketPort() {
        return config.webSocketPort();
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

    private static byte[] loadUiHtml(int wsPort, boolean passwordLogin) {
        String resource = "observe/index.html";
        try (InputStream is = ObserveCommand.class.getClassLoader().getResourceAsStream(resource)) {
            if (is == null) {
                return "<h1>Missing observe/index.html resource</h1>".getBytes(StandardCharsets.UTF_8);
            }
            String html = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            html = html.replace("{{WS_PORT}}", String.valueOf(wsPort))
                    .replace("{{PASSWORD_LOGIN}}", String.valueOf(passwordLogin));
            return html.getBytes(StandardCharsets.UTF_8);
        } catch (IOException e) {
            return ("<h1>Failed to load UI resource</h1><pre>" + e.getMessage() + "</pre>")
                    .getBytes(StandardCharsets.UTF_8);
        }
    }

    static final class SnapshotBroadcaster extends WebSocketServer {

        private final ApiAuth auth;
        private final int dashboardPort;

        SnapshotBroadcaster(String host, int port, ApiAuth auth, int dashboardPort) {
            super(new InetSocketAddress("0.0.0.0".equals(host) ? "0.0.0.0" : host, port));
            this.auth = Objects.requireNonNull(auth, "auth");
            this.dashboardPort = dashboardPort;
            setReuseAddr(true);
        }

        /** Snapshots carry goals and contexts, so the stream is held to the same credentials as /api. */
        @Override
        public ServerHandshakeBuilder onWebsocketHandshakeReceivedAsServer(
                WebSocket conn, Draft draft, ClientHandshake request) throws InvalidDataException {
            ServerHandshakeBuilder response = super.onWebsocketHandshakeReceivedAsServer(conn, draft, request);
            boolean allowed = auth.allowsWebSocket(
                    request.getFieldValue("Authorization"),
                    request.getFieldValue("Cookie"),
                    request.getFieldValue("Origin"),
                    request.getFieldValue("Host"),
                    dashboardPort);
            if (!allowed) {
                throw new InvalidDataException(CloseFrame.POLICY_VALIDATION, "Unauthorized");
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
