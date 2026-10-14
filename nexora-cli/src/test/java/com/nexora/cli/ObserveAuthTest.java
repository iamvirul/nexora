package com.nexora.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.nexora.api.NexoraEngine;
import com.nexora.api.auth.ApiAuth;
import com.nexora.api.auth.SessionEndpoints;
import com.nexora.api.observability.NexoraObservability;
import com.nexora.persistence.jdbc.JdbcExecutionStore;
import com.sun.net.httpserver.HttpServer;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The observe server end to end: which routes demand credentials, the dashboard login, and the WebSocket. */
class ObserveAuthTest {

    private static final String LOOPBACK = "127.0.0.1";
    private static final Set<String> HTTP_METHODS = Set.of("get", "put", "post", "delete", "patch");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient client = HttpClient.newHttpClient();
    private NexoraEngine engine;
    private NexoraObservability observability;
    private ApiAuth auth;
    private HttpServer server;

    @BeforeEach
    void setUp() throws Exception {
        engine = NexoraEngine.builder()
                .withExecutionStore(JdbcExecutionStore.h2InMemory())
                .build();
        observability = NexoraObservability.attach(engine);
        auth = TestAuth.auth();
        server = HttpServer.create(new InetSocketAddress(LOOPBACK, 0), 0);
        ObserveCommand.registerRoutes(server, engine, observability, "<html></html>".getBytes(), auth);
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        observability.close();
        engine.close();
    }

    @Test
    void everyDocumentedApiOperationRejectsAMissingOrWrongKey() throws Exception {
        List<String> unprotected = new ArrayList<>();
        for (Map.Entry<String, JsonNode> path : spec().path("paths").properties()) {
            if (!path.getKey().startsWith("/api/")) continue;
            for (Map.Entry<String, JsonNode> op : path.getValue().properties()) {
                if (!HTTP_METHODS.contains(op.getKey())) continue;
                String method = op.getKey().toUpperCase();
                String target = path.getKey().replaceAll("\\{[^}]+}", "probe-id");
                for (String authorization : new String[] {null, "Bearer wrong-key-0123456789abcdefghijkl"}) {
                    HttpResponse<String> response = send(
                            request(target, authorization).method(method, HttpRequest.BodyPublishers.ofString("{}")));
                    if (!isUnauthorizedJson(response)) {
                        unprotected.add(method + " " + path.getKey() + " -> " + response.statusCode());
                    }
                }
            }
        }

        assertEquals(List.of(), unprotected);
    }

    @Test
    void executeWithoutAKeyIsRejectedAndRunsNothing() throws Exception {
        HttpResponse<String> response = send(request(ExecutionEndpoints.EXECUTE_PATH, null)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"goal\":\"process order\"}")));

        assertEquals(401, response.statusCode());
        assertEquals(
                "Bearer realm=\"nexora\"",
                response.headers().firstValue("WWW-Authenticate").orElseThrow());
        assertEquals(0, observability.processSnapshot().executions().size());
    }

    @Test
    void unknownApiPathsAreUnauthorizedRatherThanNotFound() throws Exception {
        assertEquals(401, send(request("/api/does-not-exist", null).GET()).statusCode());
        assertEquals(
                404,
                send(request("/api/does-not-exist", "Bearer " + TestAuth.KEY).GET())
                        .statusCode());
    }

    @Test
    void probesScrapersDashboardAndDocsNeedNoKey() throws Exception {
        for (String path :
                List.of("/metrics", "/health/live", "/health/ready", "/health", "/", "/openapi.yaml", "/docs")) {
            assertEquals(200, send(request(path, null).GET()).statusCode(), path);
        }
    }

    @Test
    void validKeyReachesTheHandler() throws Exception {
        HttpResponse<String> response =
                send(request("/api/process", "Bearer " + TestAuth.KEY).GET());

        assertEquals(200, response.statusCode());
    }

    @Test
    void dashboardLoginGivesACookieThatWorksUntilLogout() throws Exception {
        HttpResponse<String> login = send(
                request(SessionEndpoints.PATH, "Bearer " + TestAuth.KEY).POST(HttpRequest.BodyPublishers.noBody()));
        String setCookie = login.headers().firstValue("Set-Cookie").orElseThrow();
        String cookie = setCookie.substring(0, setCookie.indexOf(';'));

        assertEquals(200, login.statusCode());
        assertTrue(setCookie.contains("HttpOnly") && setCookie.contains("SameSite=Strict"), setCookie);
        assertEquals(
                200,
                send(request("/api/process", null).header("Cookie", cookie).GET())
                        .statusCode());

        HttpResponse<String> logout = send(request(SessionEndpoints.PATH, null)
                .header("Cookie", cookie)
                .header("Origin", origin())
                .DELETE());

        assertEquals(200, logout.statusCode());
        assertTrue(logout.headers().firstValue("Set-Cookie").orElseThrow().contains("Max-Age=0"));
        assertEquals(
                401,
                send(request("/api/process", null).header("Cookie", cookie).GET())
                        .statusCode());
    }

    @Test
    void cookieMutationsNeedThisServersOrigin() throws Exception {
        String cookie = login();
        String body = "{\"goal\":\"process order\"}";

        HttpResponse<String> noOrigin = send(request(ExecutionEndpoints.EXECUTE_PATH, null)
                .header("Cookie", cookie)
                .POST(HttpRequest.BodyPublishers.ofString(body)));
        HttpResponse<String> foreignOrigin = send(request(ExecutionEndpoints.EXECUTE_PATH, null)
                .header("Cookie", cookie)
                .header("Origin", "http://" + LOOPBACK + ":1")
                .POST(HttpRequest.BodyPublishers.ofString(body)));
        HttpResponse<String> ownOrigin = send(request(ExecutionEndpoints.EXECUTE_PATH, null)
                .header("Cookie", cookie)
                .header("Origin", origin())
                .POST(HttpRequest.BodyPublishers.ofString(body)));

        assertEquals(401, noOrigin.statusCode());
        assertEquals(401, foreignOrigin.statusCode());
        assertNotEquals(401, ownOrigin.statusCode());
    }

    @Test
    void onlyTheApiKeyCanOpenASession() throws Exception {
        String cookie = login();

        HttpResponse<String> withCookie = send(request(SessionEndpoints.PATH, null)
                .header("Cookie", cookie)
                .header("Origin", origin())
                .POST(HttpRequest.BodyPublishers.noBody()));
        HttpResponse<String> wrongKey = send(request(SessionEndpoints.PATH, "Bearer not-the-key-0123456789abcdefghij")
                .POST(HttpRequest.BodyPublishers.noBody()));

        assertEquals(401, withCookie.statusCode());
        assertEquals(401, wrongKey.statusCode());
        assertTrue(wrongKey.headers().firstValue("Set-Cookie").isEmpty());
    }

    @Test
    void webSocketRequiresCredentials() throws Exception {
        int wsPort = freePort();
        int dashboardPort = server.getAddress().getPort();
        ObserveServer.SnapshotBroadcaster broadcaster =
                new ObserveServer.SnapshotBroadcaster(LOOPBACK, wsPort, auth, dashboardPort);
        broadcaster.start();
        try {
            URI uri = URI.create("ws://" + LOOPBACK + ":" + wsPort + "/");
            WebSocket withKey = connectWithRetry(uri, "Authorization", "Bearer " + TestAuth.KEY);
            withKey.abort();

            String cookie = login();
            WebSocket dashboard = client.newWebSocketBuilder()
                    .header("Cookie", cookie)
                    .header("Origin", "http://" + LOOPBACK + ":" + dashboardPort)
                    .buildAsync(uri, new WebSocket.Listener() {})
                    .get(5, TimeUnit.SECONDS);
            dashboard.abort();

            assertRejected(client.newWebSocketBuilder(), uri);
            assertRejected(
                    client.newWebSocketBuilder().header("Cookie", cookie).header("Origin", "http://" + LOOPBACK + ":1"),
                    uri);
            assertRejected(
                    client.newWebSocketBuilder().header("Authorization", "Bearer wrong-key-0123456789abcdefghij"), uri);
        } finally {
            broadcaster.stop(500);
        }
    }

    private void assertRejected(WebSocket.Builder builder, URI uri) {
        CompletionException error = assertThrows(
                CompletionException.class,
                () -> builder.buildAsync(uri, new WebSocket.Listener() {}).join());
        assertTrue(
                error.getCause() instanceof java.net.http.WebSocketHandshakeException,
                String.valueOf(error.getCause()));
    }

    /** The broadcaster binds asynchronously; retry the first, authorized, connection until it is up. */
    private WebSocket connectWithRetry(URI uri, String header, String value) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (true) {
            try {
                return client.newWebSocketBuilder()
                        .header(header, value)
                        .buildAsync(uri, new WebSocket.Listener() {})
                        .get(5, TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException e) {
                if (System.nanoTime() > deadline || e.getCause() instanceof java.net.http.WebSocketHandshakeException) {
                    throw e;
                }
                Thread.sleep(50);
            }
        }
    }

    private String login() throws Exception {
        HttpResponse<String> response = send(
                request(SessionEndpoints.PATH, "Bearer " + TestAuth.KEY).POST(HttpRequest.BodyPublishers.noBody()));
        String setCookie = response.headers().firstValue("Set-Cookie").orElseThrow();
        return setCookie.substring(0, setCookie.indexOf(';'));
    }

    private boolean isUnauthorizedJson(HttpResponse<String> response) throws Exception {
        return response.statusCode() == 401
                && "UNAUTHORIZED"
                        .equals(JSON.readTree(response.body()).at("/error/code").asText());
    }

    private HttpRequest.Builder request(String path, String authorization) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(origin() + path));
        return authorization == null ? builder : builder.header("Authorization", authorization);
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String origin() {
        return "http://" + LOOPBACK + ":" + server.getAddress().getPort();
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static JsonNode spec() throws Exception {
        try (InputStream in = ObserveAuthTest.class.getClassLoader().getResourceAsStream("openapi.yaml")) {
            return new ObjectMapper(new YAMLFactory()).readTree(in);
        }
    }
}
