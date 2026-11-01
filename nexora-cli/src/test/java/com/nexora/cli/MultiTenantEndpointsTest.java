package com.nexora.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexora.api.NexoraEngine;
import com.nexora.api.auth.ApiAuth;
import com.nexora.api.auth.ApiKeyStore;
import com.nexora.api.auth.PasswordHasher;
import com.nexora.api.auth.SessionEndpoints;
import com.nexora.api.auth.TenantResolver;
import com.nexora.api.auth.UserStore;
import com.nexora.api.observability.NexoraObservability;
import com.nexora.core.intent.Intent;
import com.nexora.persistence.DeadLetterRecord;
import com.nexora.persistence.ExecutionRecord;
import com.nexora.persistence.ExecutionState;
import com.nexora.persistence.jdbc.JdbcExecutionStore;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The observe server's HTTP API with --multi-tenant, against a real store and real credentials. */
class MultiTenantEndpointsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String LOOPBACK = "127.0.0.1";
    private static final String PASSWORD = "correct horse battery";
    private static final Instant T0 = Instant.parse("2026-10-01T10:00:00Z");
    private static final String ACME = "acme";
    private static final String GLOBEX = "globex";
    private static final String TENANT_HEADER = TenantResolver.HEADER;

    @TempDir
    Path dir;

    private final HttpClient client = HttpClient.newHttpClient();
    private JdbcExecutionStore store;
    private NexoraEngine engine;
    private NexoraObservability observability;
    private HttpServer server;

    @BeforeEach
    void setUp() throws Exception {
        UserStore users = new UserStore(dir.resolve("users"), new PasswordHasher());
        users.add("alice", PASSWORD.toCharArray(), ACME);
        store = JdbcExecutionStore.h2InMemory();
        engine = NexoraEngine.builder().withExecutionStore(store).build();
        observability = NexoraObservability.attach(engine);
        server = HttpServer.create(new InetSocketAddress(LOOPBACK, 0), 0);
        ObserveCommand.registerRoutes(
                server,
                engine,
                observability,
                new byte[0],
                new ApiAuth(ApiKeyStore.digest(TestAuth.KEY), users),
                TenantResolver.multiTenant());
        server.start();
        insert("acme-1", ACME, T0);
        insert("acme-2", ACME, T0.plusSeconds(1));
        insert("globex-1", GLOBEX, T0.plusSeconds(2));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        observability.close();
        engine.close();
    }

    @Test
    void serverKeyWithoutTenantHeaderIsRejectedWith400OnEveryScopedRoute() throws Exception {
        for (String path : List.of(
                "/api/executions",
                "/api/executions/acme-1",
                "/api/process",
                "/api/dead-letters",
                "/api/schedules",
                "/api/webhook-deliveries/acme-1")) {
            HttpResponse<String> response = send(get(path), null);

            assertEquals(400, response.statusCode(), path);
            assertEquals("TENANT_REQUIRED", errorCode(response), path);
        }
    }

    @Test
    void routeRegisteredWithoutProtectRefusesAnonymousCallersInsteadOfScopingThem() throws Exception {
        server.createContext(
                "/unprotected",
                ObserveCommand.scoped(
                        TenantResolver.multiTenant(),
                        (exchange, scope) -> ObserveCommand.sendText(exchange, 200, "scoped to " + scope.tenantId())));

        HttpResponse<String> response = client.send(
                request("/unprotected").header(TENANT_HEADER, GLOBEX).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(401, response.statusCode());
    }

    @Test
    void invalidTenantHeaderIsRejectedWith400() throws Exception {
        HttpResponse<String> response = send(get("/api/executions"), "Acme Corp");

        assertEquals(400, response.statusCode());
        assertEquals("INVALID_TENANT_ID", errorCode(response));
    }

    @Test
    void historyForOneTenantNeverReturnsAnotherTenantsExecutions() throws Exception {
        JsonNode acme = JSON.readTree(send(get("/api/executions"), ACME).body());
        JsonNode globex = JSON.readTree(send(get("/api/executions"), GLOBEX).body());

        assertEquals(List.of("acme-2", "acme-1"), ids(acme.get("data")));
        assertEquals(2, acme.get("total").asLong());
        assertEquals(List.of("globex-1"), ids(globex.get("data")));
    }

    @Test
    void anotherTenantsExecutionAnswersLikeAnUnknownOne() throws Exception {
        assertEquals(200, send(get("/api/executions/globex-1"), GLOBEX).statusCode());
        assertEquals(
                ACME,
                JSON.readTree(send(get("/api/executions/acme-1"), ACME).body())
                        .get("tenantId")
                        .asText());

        HttpResponse<String> foreign = send(get("/api/executions/globex-1"), ACME);
        HttpResponse<String> unknown = send(get("/api/executions/no-such-id"), ACME);

        assertEquals(404, foreign.statusCode());
        assertEquals(
                JSON.readTree(unknown.body()).get("error"),
                JSON.readTree(foreign.body()).get("error"));
        assertEquals(404, send(post("/api/executions/globex-1/replay"), ACME).statusCode());
        assertEquals(404, send(delete("/api/executions/globex-1"), ACME).statusCode());
        assertEquals(404, send(get("/api/webhook-deliveries/globex-1"), ACME).statusCode());
        assertEquals(200, send(get("/api/webhook-deliveries/acme-1"), ACME).statusCode());
    }

    @Test
    void executeRunsForTheRequestsTenant() throws Exception {
        HttpResponse<String> accepted = send(
                authorized("/api/execute")
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"goal\":\"anything\"}")),
                GLOBEX);
        String executionId = JSON.readTree(accepted.body()).get("executionId").asText();

        assertEquals(202, accepted.statusCode());
        assertEquals(GLOBEX, awaitStored(executionId).tenantId());
    }

    @Test
    void userKeyIsScopedToTheUsersTenantAndCannotNameAnother() throws Exception {
        String key = JSON.readTree(loginForKey().body()).get("apiKey").asText();

        HttpResponse<String> own = client.send(
                request("/api/executions")
                        .header("Authorization", "Bearer " + key)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> other = client.send(
                request("/api/executions")
                        .header("Authorization", "Bearer " + key)
                        .header(TENANT_HEADER, GLOBEX)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(List.of("acme-2", "acme-1"), ids(JSON.readTree(own.body()).get("data")));
        assertEquals(403, other.statusCode());
        assertEquals("TENANT_FORBIDDEN", errorCode(other));
    }

    @Test
    void keyHolderDashboardSessionMustNameItsTenantAndIsPinnedToIt() throws Exception {
        HttpResponse<String> unnamed = client.send(
                request(SessionEndpoints.PATH)
                        .header("Authorization", "Bearer " + TestAuth.KEY)
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> named = client.send(
                request(SessionEndpoints.PATH)
                        .header("Authorization", "Bearer " + TestAuth.KEY)
                        .header(TENANT_HEADER, GLOBEX)
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        String setCookie = named.headers().firstValue("Set-Cookie").orElseThrow();
        String cookie = setCookie.substring(0, setCookie.indexOf(';'));

        HttpResponse<String> history = client.send(
                request("/api/executions").header("Cookie", cookie).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        JsonNode who = JSON.readTree(client.send(
                        request(SessionEndpoints.PATH)
                                .header("Cookie", cookie)
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString())
                .body());

        assertEquals(400, unnamed.statusCode());
        assertEquals("TENANT_REQUIRED", errorCode(unnamed));
        assertEquals(200, named.statusCode());
        assertEquals(List.of("globex-1"), ids(JSON.readTree(history.body()).get("data")));
        assertEquals(GLOBEX, who.get("tenantId").asText());
        assertTrue(who.get("multiTenant").asBoolean());
    }

    @Test
    void deadLettersAreListedAndActedOnWithinTheirTenant() throws Exception {
        store.createDeadLetter(DeadLetterRecord.pending("dl-acme", "acme-1", "g", Map.of(), "X", "x", T0, ACME));
        store.createDeadLetter(DeadLetterRecord.pending("dl-globex", "globex-1", "g", Map.of(), "X", "x", T0, GLOBEX));

        JsonNode acme =
                JSON.readTree(send(get("/api/dead-letters?state=ALL"), ACME).body());

        assertEquals(List.of("dl-acme"), deadLetterIds(acme.get("items")));
        assertEquals(
                404, send(post("/api/dead-letters/dl-globex/resolve"), ACME).statusCode());
        assertEquals(404, send(post("/api/dead-letters/dl-globex/replay"), ACME).statusCode());
        assertEquals(
                "PENDING",
                store.findDeadLetterById("dl-globex")
                        .orElseThrow()
                        .reviewState()
                        .name());
        assertEquals(200, send(post("/api/dead-letters/dl-acme/resolve"), ACME).statusCode());
    }

    @Test
    void schedulesAreCreatedListedAndCancelledWithinTheirTenant() throws Exception {
        HttpResponse<String> created = send(
                authorized("/api/schedules")
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"cronExpression\":\"0 0 * * *\",\"goal\":\"nightly\"}")),
                ACME);
        String id = JSON.readTree(created.body()).get("id").asText();

        JsonNode acme = JSON.readTree(send(get("/api/schedules"), ACME).body());
        JsonNode globex = JSON.readTree(send(get("/api/schedules"), GLOBEX).body());
        HttpResponse<String> foreignCancel = send(delete("/api/schedules/" + id), GLOBEX);

        assertEquals(201, created.statusCode());
        assertEquals(1, acme.get("schedules").size());
        assertEquals(ACME, acme.get("schedules").get(0).get("tenantId").asText());
        assertEquals(0, globex.get("schedules").size());
        assertEquals(404, foreignCancel.statusCode());
        assertTrue(store.findScheduleById(id).orElseThrow().active());
        assertEquals(200, send(delete("/api/schedules/" + id), ACME).statusCode());
        assertFalse(store.findScheduleById(id).orElseThrow().active());
    }

    @Test
    void processSnapshotShowsOnlyTheTenantsLiveExecutions() throws Exception {
        String acmeRun = engine.execute(new Intent("anything", Map.of()).withTenantId(ACME))
                .get()
                .executionId();
        String globexRun = engine.execute(new Intent("anything", Map.of()).withTenantId(GLOBEX))
                .get()
                .executionId();

        JsonNode snapshot = JSON.readTree(send(get("/api/process"), ACME).body());
        List<String> visible = new ArrayList<>();
        snapshot.get("executions").forEach(e -> visible.add(e.get("executionId").asText()));

        assertTrue(visible.contains(acmeRun), visible.toString());
        assertFalse(visible.contains(globexRun), visible.toString());
    }

    private void insert(String id, String tenantId, Instant startedAt) {
        store.createExecution(new ExecutionRecord(
                id, "trace", "goal", Map.of(), ExecutionState.FAILED, startedAt, startedAt, List.of(), tenantId));
    }

    private ExecutionRecord awaitStored(String executionId) throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (store.findById(executionId).isEmpty()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("execution was never stored: " + executionId);
            }
            Thread.sleep(20);
        }
        return store.findById(executionId).orElseThrow();
    }

    private HttpResponse<String> loginForKey() throws Exception {
        return client.send(
                request(SessionEndpoints.KEYS_PATH)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"username\":\"alice\",\"password\":\"" + PASSWORD + "\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder get(String path) {
        return authorized(path).GET();
    }

    private HttpRequest.Builder post(String path) {
        return authorized(path).POST(HttpRequest.BodyPublishers.noBody());
    }

    private HttpRequest.Builder delete(String path) {
        return authorized(path).DELETE();
    }

    private HttpRequest.Builder authorized(String path) {
        return TestAuth.authorized(uri(path));
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(uri(path));
    }

    private URI uri(String path) {
        return URI.create("http://" + LOOPBACK + ":" + server.getAddress().getPort() + path);
    }

    /** Sends with the server API key, naming {@code tenant} in the header when it is not null. */
    private HttpResponse<String> send(HttpRequest.Builder builder, String tenant) throws Exception {
        if (tenant != null) {
            builder.header(TENANT_HEADER, tenant);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String errorCode(HttpResponse<String> response) throws Exception {
        return JSON.readTree(response.body()).path("error").path("code").asText();
    }

    private static List<String> ids(JsonNode rows) {
        List<String> ids = new ArrayList<>();
        rows.forEach(row -> ids.add(row.get("executionId").asText()));
        return ids;
    }

    private static List<String> deadLetterIds(JsonNode rows) {
        List<String> ids = new ArrayList<>();
        rows.forEach(row -> ids.add(row.get("id").asText()));
        return ids;
    }
}
