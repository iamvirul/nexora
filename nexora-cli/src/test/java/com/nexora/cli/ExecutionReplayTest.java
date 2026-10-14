package com.nexora.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexora.api.NexoraEngine;
import com.nexora.core.capability.CapabilityResult;
import com.nexora.core.execution.ExecutionResult;
import com.nexora.core.execution.ExecutionStatus;
import com.nexora.core.intent.Intent;
import com.nexora.persistence.jdbc.JdbcExecutionStore;
import com.nexora.planner.model.StepDefinition;
import com.nexora.spi.Capability;
import com.nexora.spi.CapabilityDescriptor;
import com.nexora.spi.CapabilityProvider;
import com.nexora.spi.NexoraPlugin;
import com.nexora.spi.PluginContext;
import com.nexora.spi.PluginDescriptor;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/** End-to-end replay over HTTP: the REST route and {@code nexora replay}, against a real engine and store. */
class ExecutionReplayTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String LOOPBACK = "127.0.0.1";
    private static final long WAIT_SECONDS = 5;

    private final HttpClient client = HttpClient.newHttpClient();
    // The first charge fails so the first run ends FAILED; every later charge succeeds.
    private final AtomicInteger failuresLeft = new AtomicInteger(1);

    private JdbcExecutionStore store;
    private NexoraEngine engine;
    private HttpServer server;

    @BeforeEach
    void setUp() throws IOException {
        store = JdbcExecutionStore.h2InMemory();
        engine = NexoraEngine.builder()
                .withExecutionStore(store)
                .withPlugin(plugin())
                .withStepDefinition(StepDefinition.builder("charge", "charge")
                        .withMatcher(goal -> goal.contains("order"))
                        .build())
                .build();
        server = startServer(engine);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        engine.close();
    }

    @Test
    void replayOfFailedExecutionReturns202WithNewExecutionId() throws Exception {
        ExecutionResult failed = run();
        assertEquals(ExecutionStatus.FAILED, failed.status());

        HttpResponse<String> response = post(failed.executionId());

        assertEquals(202, response.statusCode());
        JsonNode body = json(response);
        assertNotEquals(failed.executionId(), body.get("executionId").asText());
        assertEquals(failed.executionId(), body.get("replayOf").asText());
    }

    @Test
    void replayOfCompletedExecutionReturns409WithState() throws Exception {
        failuresLeft.set(0);
        ExecutionResult completed = run();

        HttpResponse<String> response = post(completed.executionId());

        assertEquals(409, response.statusCode());
        assertEquals("COMPLETED", json(response).get("state").asText());
    }

    @Test
    void replayOfUnknownExecutionReturns404() throws Exception {
        assertEquals(404, post("missing").statusCode());
    }

    @Test
    void replayRouteOnlyAcceptsPost() throws Exception {
        HttpResponse<String> response =
                client.send(TestAuth.authorized(replayUri("any")).GET().build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(405, response.statusCode());
        assertEquals("POST", response.headers().firstValue("Allow").orElse(null));
    }

    @Test
    void replayWithoutPersistenceReturns503() throws Exception {
        try (NexoraEngine noStore = NexoraEngine.builder().build()) {
            HttpServer bare = startServer(noStore);
            try {
                URI uri = URI.create(
                        "http://" + LOOPBACK + ":" + bare.getAddress().getPort() + ExecutionEndpoints.PATH_PREFIX
                                + "any" + ExecutionEndpoints.REPLAY_SUFFIX);
                HttpResponse<String> response = client.send(
                        TestAuth.authorized(uri)
                                .POST(HttpRequest.BodyPublishers.noBody())
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(503, response.statusCode());
            } finally {
                bare.stop(0);
            }
        }
    }

    @Test
    void replayCommandStartsReplay() throws Exception {
        ExecutionResult failed = run();

        CommandResult result = runReplayCommand(failed.executionId(), "--server", serverUrl());

        assertEquals(ObserveServerClient.EXIT_OK, result.exitCode(), result.err());
        assertTrue(result.out().contains("Replay started"), result.out());
        assertTrue(result.out().contains("replay of " + failed.executionId()), result.out());
    }

    @Test
    void replayCommandExplainsWhyExecutionCannotBeReplayed() throws Exception {
        failuresLeft.set(0);
        ExecutionResult completed = run();

        CommandResult result = runReplayCommand(completed.executionId(), "--server", serverUrl());

        assertEquals(ObserveServerClient.EXIT_FAILED, result.exitCode());
        assertTrue(result.err().contains("cannot be replayed (state COMPLETED)"), result.err());
    }

    @Test
    void replayCommandReportsUnknownExecution() {
        CommandResult result = runReplayCommand("missing", "--server", serverUrl());

        assertEquals(ObserveServerClient.EXIT_FAILED, result.exitCode());
        assertTrue(result.err().contains("no execution found"), result.err());
    }

    private ExecutionResult run() throws Exception {
        return engine.execute(new Intent("process order", Map.of("orderId", "ORD-1")))
                .get(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    private HttpResponse<String> post(String executionId) throws Exception {
        return client.send(
                TestAuth.authorized(replayUri(executionId))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private URI replayUri(String executionId) {
        return URI.create(
                serverUrl() + ExecutionEndpoints.PATH_PREFIX + executionId + ExecutionEndpoints.REPLAY_SUFFIX);
    }

    private String serverUrl() {
        return "http://" + LOOPBACK + ":" + server.getAddress().getPort();
    }

    private static HttpServer startServer(NexoraEngine engine) throws IOException {
        HttpServer http = HttpServer.create(new InetSocketAddress(LOOPBACK, 0), 0);
        new ExecutionEndpoints(engine).register(http, TestAuth.auth());
        http.start();
        return http;
    }

    private static JsonNode json(HttpResponse<String> response) throws IOException {
        return JSON.readTree(response.body());
    }

    private static CommandResult runReplayCommand(String... args) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine cmd = new CommandLine(new ReplayCommand());
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));
        int exitCode = cmd.execute(withTestKey(args));
        return new CommandResult(exitCode, out.toString(), err.toString());
    }

    private record CommandResult(int exitCode, String out, String err) {}

    private NexoraPlugin plugin() {
        return new NexoraPlugin() {
            @Override
            public PluginDescriptor descriptor() {
                return new PluginDescriptor("replay-cli-test", "1.0", "replay cli test plugin", List.of(), null);
            }

            @Override
            public void initialize(PluginContext ctx) {}

            @Override
            public List<CapabilityProvider> capabilityProviders() {
                return List.of(new CapabilityProvider() {
                    @Override
                    public CapabilityDescriptor descriptor() {
                        return new CapabilityDescriptor("charge", "charge", List.of(), List.of(), true, false);
                    }

                    @Override
                    public Capability create(PluginContext ctx) {
                        return req -> failuresLeft.getAndDecrement() > 0
                                ? CapabilityResult.failure("DECLINED", "card declined")
                                : CapabilityResult.success("charged");
                    }
                });
            }

            @Override
            public void shutdown() {}
        };
    }

    /** The server under test requires the API key, as a real one does. */
    private static String[] withTestKey(String... args) {
        String[] withKey = java.util.Arrays.copyOf(args, args.length + 2);
        withKey[args.length] = "--api-key";
        withKey[args.length + 1] = TestAuth.KEY;
        return withKey;
    }
}
