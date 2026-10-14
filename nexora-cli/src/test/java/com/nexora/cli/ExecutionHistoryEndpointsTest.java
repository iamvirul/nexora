package com.nexora.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexora.api.NexoraEngine;
import com.nexora.persistence.ExecutionRecord;
import com.nexora.persistence.ExecutionState;
import com.nexora.persistence.StepRecord;
import com.nexora.persistence.jdbc.JdbcExecutionStore;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ExecutionHistoryEndpointsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String LOOPBACK = "127.0.0.1";
    private static final Instant T0 = Instant.parse("2026-10-01T10:00:00Z");

    private final HttpClient client = HttpClient.newHttpClient();

    private JdbcExecutionStore store;
    private NexoraEngine engine;
    private HttpServer server;

    @BeforeEach
    void setUp() throws IOException {
        store = JdbcExecutionStore.h2InMemory();
        engine = NexoraEngine.builder().withExecutionStore(store).build();
        server = startServer(engine);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        engine.close();
    }

    @Test
    void listsNewestFirstWithTotalAndCursorPagination() throws Exception {
        for (int i = 0; i < 5; i++) {
            insert("e" + i, "process order", ExecutionState.COMPLETED, T0.plusSeconds(i));
        }

        List<String> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            JsonNode body = getJson("/api/executions?limit=2" + (cursor == null ? "" : "&cursor=" + cursor), 200);
            assertEquals(5, body.get("total").asLong());
            body.get("data").forEach(row -> seen.add(row.get("executionId").asText()));
            cursor = body.get("cursor").isNull() ? null : body.get("cursor").asText();
            pages++;
        } while (cursor != null);

        assertEquals(List.of("e4", "e3", "e2", "e1", "e0"), seen);
        assertEquals(3, pages);
    }

    @Test
    void listRowsAreSummariesWithIsoTimestampsAndDuration() throws Exception {
        store.createExecution(new ExecutionRecord(
                "done",
                "trace-1",
                "process order",
                Map.of("secret", "x"),
                ExecutionState.COMPLETED,
                T0,
                T0.plusMillis(1500),
                List.of()));

        JsonNode row = getJson("/api/executions", 200).get("data").get(0);

        assertEquals("COMPLETED", row.get("state").asText());
        assertEquals("2026-10-01T10:00:00Z", row.get("startedAt").asText());
        assertEquals(1500, row.get("durationMs").asLong());
        assertTrue(row.get("context") == null, "context belongs to the detail route only");
    }

    @Test
    void filtersByStatusCaseInsensitively() throws Exception {
        insert("ok", "goal", ExecutionState.COMPLETED, T0);
        insert("bad", "goal", ExecutionState.FAILED, T0.plusSeconds(1));
        insert("stop", "goal", ExecutionState.CANCELLED, T0.plusSeconds(2));

        JsonNode body = getJson("/api/executions?status=failed,CANCELLED", 200);

        assertEquals(List.of("stop", "bad"), ids(body));
        assertEquals(2, body.get("total").asLong());
    }

    @Test
    void filtersByGoalSubstring() throws Exception {
        insert("pay", "process payment", ExecutionState.COMPLETED, T0);
        insert("ship", "ship order", ExecutionState.COMPLETED, T0.plusSeconds(1));

        assertEquals(List.of("pay"), ids(getJson("/api/executions?goal=PAYMENT", 200)));
    }

    @Test
    void filtersByTimeRangeWithEncodedOffsets() throws Exception {
        insert("early", "goal", ExecutionState.COMPLETED, T0.minusSeconds(60));
        insert("inside", "goal", ExecutionState.COMPLETED, T0.plusSeconds(60));
        insert("late", "goal", ExecutionState.COMPLETED, T0.plusSeconds(7200));

        // 15:30+05:30 == 10:00Z, 16:30+05:30 == 11:00Z
        JsonNode body = getJson("/api/executions?from=2026-10-01T15:30:00%2B05:30&to=2026-10-01T16:30:00%2B05:30", 200);

        assertEquals(List.of("inside"), ids(body));
    }

    @Test
    void rejectsInvalidQueryParametersWith400() throws Exception {
        String[] invalid = {
            "limit=0",
            "limit=101",
            "limit=abc",
            "status=BOGUS",
            "from=yesterday",
            "from=2026-10-01T11:00:00Z&to=2026-10-01T10:00:00Z",
            "cursor=not-a-cursor",
            "limit=",
            "cursor=",
            "status=",
            "from=",
            "goal=" + "x".repeat(201)
        };
        for (String query : invalid) {
            JsonNode body = getJson("/api/executions?" + query, 400);
            assertTrue(body.hasNonNull("error"), query);
        }
    }

    @Test
    void detailReturnsExecutionWithStepsAndContext() throws Exception {
        store.createExecution(new ExecutionRecord(
                "e1",
                "trace-1",
                "process order",
                Map.of("orderId", "ORD-1"),
                ExecutionState.RUNNING,
                T0,
                null,
                List.of()));
        store.upsertStep(
                "e1", StepRecord.started("validate", "validate_order", "k1", T0).completed(T0.plusMillis(20)));

        JsonNode body = getJson("/api/executions/e1", 200);

        assertEquals("e1", body.get("executionId").asText());
        assertEquals("ORD-1", body.at("/context/orderId").asText());
        assertEquals("validate", body.at("/steps/0/stepId").asText());
        assertEquals("COMPLETED", body.at("/steps/0/state").asText());
        assertEquals(20, body.at("/steps/0/durationMs").asLong());
    }

    @Test
    void detailReturns404ForUnknownExecution() throws Exception {
        assertEquals(
                "Execution not found",
                getJson("/api/executions/nope", 404).get("error").asText());
    }

    @Test
    void detailReturns500NotFoundWhenStoreIsDown() throws Exception {
        insert("e1", "goal", ExecutionState.COMPLETED, T0);
        store.close();

        assertEquals(
                "Execution detail query failed",
                getJson("/api/executions/e1", 500).get("error").asText());
        assertEquals(
                "Execution history query failed",
                getJson("/api/executions", 500).get("error").asText());
    }

    @Test
    void historyReturns503WithoutPersistence() throws Exception {
        try (NexoraEngine noStore = NexoraEngine.builder().build()) {
            HttpServer bare = startServer(noStore);
            try {
                URI base = URI.create(
                        "http://" + LOOPBACK + ":" + bare.getAddress().getPort());
                assertEquals(503, get(base.resolve("/api/executions")).statusCode());
                assertEquals(503, get(base.resolve("/api/executions/e1")).statusCode());
            } finally {
                bare.stop(0);
            }
        }
    }

    @Test
    void rejectsUnsupportedMethodsWithAllowHeader() throws Exception {
        HttpResponse<String> postList = send(TestAuth.authorized(uri("/api/executions"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build());
        HttpResponse<String> putDetail = send(TestAuth.authorized(uri("/api/executions/e1"))
                .PUT(HttpRequest.BodyPublishers.noBody())
                .build());

        assertEquals(405, postList.statusCode());
        assertEquals("GET", postList.headers().firstValue("Allow").orElse(null));
        assertEquals(405, putDetail.statusCode());
        assertEquals("GET, DELETE", putDetail.headers().firstValue("Allow").orElse(null));
    }

    @Test
    void pathsThatOnlySharePrefixAre404() throws Exception {
        assertEquals(404, get(uri("/api/executionsfoo")).statusCode());
    }

    private static HttpServer startServer(NexoraEngine engine) throws IOException {
        HttpServer http = HttpServer.create(new InetSocketAddress(LOOPBACK, 0), 0);
        new ExecutionEndpoints(engine).register(http, TestAuth.auth());
        http.start();
        return http;
    }

    private void insert(String id, String goal, ExecutionState state, Instant startedAt) {
        store.createExecution(
                new ExecutionRecord(id, "trace-" + id, goal, Map.of(), state, startedAt, null, List.of()));
    }

    private JsonNode getJson(String pathAndQuery, int expectedStatus) throws Exception {
        HttpResponse<String> response = get(uri(pathAndQuery));
        assertEquals(expectedStatus, response.statusCode(), pathAndQuery + " -> " + response.body());
        return JSON.readTree(response.body());
    }

    private HttpResponse<String> get(URI target) throws Exception {
        return send(TestAuth.authorized(target).GET().build());
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String pathAndQuery) {
        return URI.create("http://" + LOOPBACK + ":" + server.getAddress().getPort() + pathAndQuery);
    }

    private static List<String> ids(JsonNode body) {
        List<String> ids = new ArrayList<>();
        body.get("data").forEach(row -> ids.add(row.get("executionId").asText()));
        return ids;
    }
}
