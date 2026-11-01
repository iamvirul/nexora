package com.nexora.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexora.api.auth.TenantResolver;
import com.nexora.api.observability.NexoraObservability.ExecutionSnapshot;
import com.nexora.api.observability.NexoraObservability.ProcessSnapshot;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The live stream on a multi-tenant server: each socket only ever receives its own tenant's executions. */
class MultiTenantStreamTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String LOOPBACK = "127.0.0.1";
    private static final int DASHBOARD_PORT = 9464;

    private final HttpClient client = HttpClient.newHttpClient();
    private ObserveServer.SnapshotBroadcaster broadcaster;
    private URI uri;

    @BeforeEach
    void setUp() throws Exception {
        int port = freePort();
        broadcaster = new ObserveServer.SnapshotBroadcaster(
                LOOPBACK, port, TestAuth.auth(), TenantResolver.multiTenant(), DASHBOARD_PORT);
        broadcaster.start();
        uri = URI.create("ws://" + LOOPBACK + ":" + port + "/");
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        broadcaster.stop(500);
    }

    @Test
    void eachSocketReceivesOnlyItsTenantsExecutions() throws Exception {
        BlockingQueue<String> acme = new LinkedBlockingQueue<>();
        BlockingQueue<String> globex = new LinkedBlockingQueue<>();
        WebSocket acmeSocket = connect("acme", acme);
        WebSocket globexSocket = connect("globex", globex);

        broadcaster.broadcastSnapshot(new ProcessSnapshot(
                1L, 2, List.of(execution("a-1", "acme"), execution("g-1", "globex"), execution("a-2", "acme"))));

        JsonNode acmeView = JSON.readTree(acme.poll(5, TimeUnit.SECONDS));
        JsonNode globexView = JSON.readTree(globex.poll(5, TimeUnit.SECONDS));
        acmeSocket.abort();
        globexSocket.abort();

        assertEquals(List.of("a-1", "a-2"), ids(acmeView));
        assertEquals(2, acmeView.get("activeExecutions").asInt());
        assertEquals(List.of("g-1"), ids(globexView));
        assertEquals(1, globexView.get("activeExecutions").asInt());
    }

    @Test
    void serverKeyWithoutTenantCannotOpenTheStream() throws Exception {
        // The broadcaster binds asynchronously: a refused connection means not up yet, so retry until
        // the server answers, then expect it to reject the handshake.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        Throwable cause;
        while (true) {
            CompletionException error = assertThrows(
                    CompletionException.class,
                    () -> client.newWebSocketBuilder()
                            .header("Authorization", "Bearer " + TestAuth.KEY)
                            .buildAsync(uri, new WebSocket.Listener() {})
                            .join());
            cause = error.getCause();
            if (cause instanceof WebSocketHandshakeException || System.nanoTime() > deadline) {
                break;
            }
            Thread.sleep(50);
        }

        assertTrue(cause instanceof WebSocketHandshakeException, String.valueOf(cause));
    }

    /** The broadcaster binds asynchronously; retry until it accepts the authorized connection. */
    private WebSocket connect(String tenant, BlockingQueue<String> received) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (true) {
            try {
                return client.newWebSocketBuilder()
                        .header("Authorization", "Bearer " + TestAuth.KEY)
                        .header(TenantResolver.HEADER, tenant)
                        .buildAsync(uri, collector(received))
                        .get(5, TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                if (System.nanoTime() > deadline || e.getCause() instanceof WebSocketHandshakeException) {
                    throw e;
                }
                Thread.sleep(50);
            }
        }
    }

    private static WebSocket.Listener collector(BlockingQueue<String> received) {
        return new WebSocket.Listener() {
            private final StringBuilder partial = new StringBuilder();

            @Override
            public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                partial.append(data);
                if (last) {
                    received.add(partial.toString());
                    partial.setLength(0);
                }
                webSocket.request(1);
                return null;
            }
        };
    }

    private static ExecutionSnapshot execution(String id, String tenantId) {
        return new ExecutionSnapshot(id, "trace", "RUNNING", 1L, 0L, 0, 0, 0, 0, 0, List.of(), List.of(), tenantId);
    }

    private static List<String> ids(JsonNode snapshot) {
        List<String> ids = new ArrayList<>();
        snapshot.get("executions").forEach(e -> ids.add(e.get("executionId").asText()));
        return ids;
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
