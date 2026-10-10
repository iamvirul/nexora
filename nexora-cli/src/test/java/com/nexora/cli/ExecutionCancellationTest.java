package com.nexora.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexora.api.NexoraEngine;
import com.nexora.core.capability.CapabilityResult;
import com.nexora.core.execution.ExecutionResult;
import com.nexora.core.execution.ExecutionStatus;
import com.nexora.core.intent.Intent;
import com.nexora.event.PlanStartedEvent;
import com.nexora.persistence.jdbc.JdbcExecutionStore;
import com.nexora.planner.model.StepDefinition;
import com.nexora.spi.Capability;
import com.nexora.spi.CapabilityDescriptor;
import com.nexora.spi.CapabilityProvider;
import com.nexora.spi.NexoraPlugin;
import com.nexora.spi.PluginContext;
import com.nexora.spi.PluginDescriptor;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests for cancellation over HTTP: a real engine with a real store behind
 * {@code DELETE /api/executions/{id}}, driven both directly and through {@code nexora cancel}.
 */
class ExecutionCancellationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String LOOPBACK = "127.0.0.1";
    private static final long WAIT_SECONDS = 5;

    private final HttpClient client = HttpClient.newHttpClient();
    private final CountDownLatch blockStarted = new CountDownLatch(1);
    private final CompletableFuture<String> startedExecutionId = new CompletableFuture<>();

    private JdbcExecutionStore store;
    private NexoraEngine engine;
    private HttpServer server;

    @BeforeEach
    void setUp() throws IOException {
        store = JdbcExecutionStore.h2InMemory();
        engine = NexoraEngine.builder()
                .withExecutionStore(store)
                .withPlugin(blockingPlugin())
                .withStepDefinition(StepDefinition.builder("block", "block")
                        .withMatcher(goal -> goal.contains("slow"))
                        .build())
                .withStepDefinition(StepDefinition.builder("quick", "quick")
                        .withMatcher(goal -> goal.contains("quick"))
                        .build())
                .build();
        engine.subscribe(PlanStartedEvent.class, e -> startedExecutionId.complete(e.executionId()));
        server = HttpServer.create(new InetSocketAddress(LOOPBACK, 0), 0);
        new ExecutionEndpoints(engine).register(server);
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        engine.close();
    }

    @Test
    void deleteCancelsRunningExecution() throws Exception {
        CompletableFuture<ExecutionResult> execution = startSlowExecution();
        String executionId = awaitRunning();

        HttpResponse<String> response = delete(executionId);

        assertEquals(200, response.statusCode());
        assertTrue(json(response).get("cancelled").asBoolean());
        assertEquals(ExecutionStatus.CANCELLED, execution.get(WAIT_SECONDS, TimeUnit.SECONDS).status());
    }

    @Test
    void deleteReturns409ForFinishedExecution() throws Exception {
        ExecutionResult finished = engine.execute(new Intent("quick job", Map.of())).get(WAIT_SECONDS, TimeUnit.SECONDS);

        HttpResponse<String> response = delete(finished.executionId());

        assertEquals(409, response.statusCode());
        assertEquals(finished.executionId(), json(response).get("executionId").asText());
    }

    @Test
    void deleteReturns404ForUnknownExecution() throws Exception {
        assertEquals(404, delete("no-such-execution").statusCode());
    }

    @Test
    void nonDeleteMethodsAreRejectedWithAllowHeader() throws Exception {
        HttpRequest get = HttpRequest.newBuilder(executionUri("anything")).GET().build();

        HttpResponse<String> response = client.send(get, HttpResponse.BodyHandlers.ofString());

        assertEquals(405, response.statusCode());
        assertEquals("DELETE", response.headers().firstValue("Allow").orElse(null));
    }

    @Test
    void missingExecutionIdReturns404() throws Exception {
        assertEquals(404, delete("").statusCode());
    }

    @Test
    void postExecuteReturnsExecutionIdThatCanBeCancelled() throws Exception {
        HttpRequest post = HttpRequest.newBuilder(URI.create(serverUrl() + ExecutionEndpoints.EXECUTE_PATH))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"goal\":\"slow job\"}"))
                .build();

        HttpResponse<String> accepted = client.send(post, HttpResponse.BodyHandlers.ofString());
        String executionId = json(accepted).get("executionId").asText();
        assertTrue(blockStarted.await(WAIT_SECONDS, TimeUnit.SECONDS), "blocking step never started");

        assertEquals(202, accepted.statusCode());
        assertEquals(startedExecutionId.get(WAIT_SECONDS, TimeUnit.SECONDS), executionId);
        assertEquals(200, delete(executionId).statusCode());
    }

    @Test
    void postExecuteRejectsMissingGoal() throws Exception {
        HttpRequest post = HttpRequest.newBuilder(URI.create(serverUrl() + ExecutionEndpoints.EXECUTE_PATH))
                .POST(HttpRequest.BodyPublishers.ofString("{\"context\":{}}"))
                .build();

        HttpResponse<String> response = client.send(post, HttpResponse.BodyHandlers.ofString());

        assertEquals(400, response.statusCode());
        assertEquals("Field 'goal' is required", json(response).get("error").asText());
    }

    @Test
    void postExecuteRejectsInvalidJson() throws Exception {
        HttpRequest post = HttpRequest.newBuilder(URI.create(serverUrl() + ExecutionEndpoints.EXECUTE_PATH))
                .POST(HttpRequest.BodyPublishers.ofString("{not json"))
                .build();

        assertEquals(400, client.send(post, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test
    void executeEndpointRejectsGet() throws Exception {
        HttpRequest get = HttpRequest.newBuilder(URI.create(serverUrl() + ExecutionEndpoints.EXECUTE_PATH)).GET().build();

        HttpResponse<String> response = client.send(get, HttpResponse.BodyHandlers.ofString());

        assertEquals(405, response.statusCode());
        assertEquals("POST", response.headers().firstValue("Allow").orElse(null));
    }

    @Test
    void cancelCommandCancelsRunningExecution() throws Exception {
        CompletableFuture<ExecutionResult> execution = startSlowExecution();
        String executionId = awaitRunning();

        CommandResult result = runCancelCommand(executionId, "--server", serverUrl());

        assertEquals(CancelCommand.EXIT_OK, result.exitCode(), result.err());
        assertTrue(result.out().contains("Cancellation requested"), result.out());
        assertEquals(ExecutionStatus.CANCELLED, execution.get(WAIT_SECONDS, TimeUnit.SECONDS).status());
    }

    @Test
    void cancelCommandReportsUnknownExecution() {
        CommandResult result = runCancelCommand("no-such-execution", "--server", serverUrl());

        assertEquals(CancelCommand.EXIT_FAILED, result.exitCode());
        assertTrue(result.err().contains("no execution found"), result.err());
    }

    @Test
    void cancelCommandReportsUnreachableServer() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }

        CommandResult result = runCancelCommand("any-id", "--server", "http://" + LOOPBACK + ":" + closedPort);

        assertEquals(CancelCommand.EXIT_FAILED, result.exitCode());
        assertTrue(result.err().contains("could not reach"), result.err());
    }

    @Test
    void cancelCommandRejectsNonHttpServerUrl() {
        CommandResult result = runCancelCommand("any-id", "--server", "file:///etc/passwd");

        assertEquals(CancelCommand.EXIT_USAGE, result.exitCode());
        assertTrue(result.err().contains("--server must be an http(s) URL"), result.err());
    }

    private CompletableFuture<ExecutionResult> startSlowExecution() {
        return engine.execute(new Intent("slow job", Map.of()));
    }

    private String awaitRunning() throws Exception {
        assertTrue(blockStarted.await(WAIT_SECONDS, TimeUnit.SECONDS), "blocking step never started");
        return startedExecutionId.get(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    private HttpResponse<String> delete(String executionId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(executionUri(executionId)).DELETE().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private URI executionUri(String executionId) {
        return URI.create(serverUrl() + ExecutionEndpoints.PATH_PREFIX + executionId);
    }

    private String serverUrl() {
        return "http://" + LOOPBACK + ":" + server.getAddress().getPort();
    }

    private static JsonNode json(HttpResponse<String> response) throws IOException {
        return JSON.readTree(response.body());
    }

    private static CommandResult runCancelCommand(String... args) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine cmd = new CommandLine(new CancelCommand());
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));
        int exitCode = cmd.execute(args);
        return new CommandResult(exitCode, out.toString(), err.toString());
    }

    private record CommandResult(int exitCode, String out, String err) {}

    private NexoraPlugin blockingPlugin() {
        return new NexoraPlugin() {
            @Override public PluginDescriptor descriptor() {
                return new PluginDescriptor("cancel-cli-test", "1.0", "cancel cli test plugin", List.of(), null);
            }
            @Override public void initialize(PluginContext ctx) {}
            @Override public List<CapabilityProvider> capabilityProviders() {
                return List.of(
                        provider("block", req -> {
                            blockStarted.countDown();
                            try {
                                new CountDownLatch(1).await();
                                return CapabilityResult.success("unreachable");
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                return CapabilityResult.failure("INTERRUPTED", "interrupted by cancel");
                            }
                        }),
                        provider("quick", req -> CapabilityResult.success("done")));
            }
            @Override public void shutdown() {}
        };
    }

    private static CapabilityProvider provider(String id, Capability capability) {
        return new CapabilityProvider() {
            @Override public CapabilityDescriptor descriptor() {
                return new CapabilityDescriptor(id, id, List.of(), List.of(), true, false);
            }
            @Override public Capability create(PluginContext ctx) {
                return capability;
            }
        };
    }
}
