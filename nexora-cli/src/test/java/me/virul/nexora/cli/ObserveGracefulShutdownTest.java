package me.virul.nexora.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import me.virul.nexora.api.NexoraEngine;
import me.virul.nexora.core.capability.CapabilityResult;
import me.virul.nexora.core.execution.ExecutionResult;
import me.virul.nexora.core.execution.ExecutionStatus;
import me.virul.nexora.core.intent.Intent;
import me.virul.nexora.core.tenant.TenantId;
import me.virul.nexora.event.EngineShutdownEvent;
import me.virul.nexora.persistence.DeadLetterRecord;
import me.virul.nexora.persistence.DeadLetterReviewState;
import me.virul.nexora.persistence.jdbc.JdbcExecutionStore;
import me.virul.nexora.planner.model.StepDefinition;
import me.virul.nexora.spi.Capability;
import me.virul.nexora.spi.CapabilityDescriptor;
import me.virul.nexora.spi.CapabilityProvider;
import me.virul.nexora.spi.NexoraPlugin;
import me.virul.nexora.spi.PluginContext;
import me.virul.nexora.spi.PluginDescriptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** SIGTERM handling of {@code nexora observe}: the engine drains while the HTTP server still answers. */
class ObserveGracefulShutdownTest {

    private static final String LOOPBACK = "127.0.0.1";
    private static final long WAIT_SECONDS = 10;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient client = HttpClient.newHttpClient();
    private final CountDownLatch stepStarted = new CountDownLatch(1);
    private final CountDownLatch stepRelease = new CountDownLatch(1);

    private NexoraEngine engine;
    private ObserveServer server;

    @BeforeEach
    void setUp() throws IOException {
        engine = NexoraEngine.builder()
                .withShutdownHook(false)
                .withShutdownDrainTimeout(Duration.ofSeconds(WAIT_SECONDS))
                .withPlugin(plugin())
                .withStepDefinition(StepDefinition.builder("slow", "slow")
                        .withMatcher(goal -> goal.contains("order"))
                        .build())
                .build();
        int[] ports = freePorts();
        server = ObserveServer.start(
                engine,
                TestAuth.auth(),
                new ObserveServer.Config(
                        LOOPBACK,
                        ports[0],
                        ports[1],
                        ObserveServer.Config.DEFAULT_MAX_EXECUTIONS,
                        ObserveServer.Config.DEFAULT_MAX_EVENTS));
    }

    @AfterEach
    void tearDown() {
        stepRelease.countDown();
        server.close();
        engine.shutdown(Duration.ZERO);
    }

    @Test
    void serverAnswers503AndReadinessDownWhileTheEngineDrainsThenStops() throws Exception {
        CompletableFuture<ExecutionResult> inFlight = engine.execute(new Intent("process order", Map.of()));
        assertTrue(stepStarted.await(WAIT_SECONDS, TimeUnit.SECONDS), "slow step started");

        CompletableFuture<Void> stopping =
                CompletableFuture.runAsync(() -> ObserveCommand.stopGracefully(engine, server));
        awaitDraining();

        HttpResponse<String> execute = postExecute();
        assertEquals(503, execute.statusCode());
        assertEquals("Engine is shutting down", json(execute).get("error").asText());
        HttpResponse<String> ready = get("/health/ready");
        assertEquals(503, ready.statusCode());
        assertEquals("DOWN", json(ready).get("checks").get("executor").asText());
        assertFalse(stopping.isDone(), "server stays up until the drain finishes");

        stepRelease.countDown();
        stopping.get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertEquals(
                ExecutionStatus.COMPLETED,
                inFlight.get(WAIT_SECONDS, TimeUnit.SECONDS).status());
        assertThrows(IOException.class, () -> get("/health/live"), "server stopped after the drain");
    }

    @Test
    void serverStopsEvenWhenTheEngineIsAlreadyShutDown() throws Exception {
        engine.shutdown(Duration.ZERO);

        ObserveCommand.stopGracefully(engine, server);

        assertThrows(IOException.class, () -> get("/health/live"));
    }

    @Test
    void deadLetterReplayDuringShutdownAnswers503AndLeavesItPending() throws Exception {
        JdbcExecutionStore store = JdbcExecutionStore.h2InMemory();
        store.createDeadLetter(DeadLetterRecord.pending(
                "dl-1", "exec-1", "process order", Map.of(), "STEP_FAILED", "boom", Instant.now(), TenantId.DEFAULT));
        NexoraEngine stored = NexoraEngine.builder()
                .withShutdownHook(false)
                .withExecutionStore(store)
                .withPlugin(plugin())
                .build();
        int[] ports = freePorts();
        ObserveServer storedServer = ObserveServer.start(
                stored,
                TestAuth.auth(),
                new ObserveServer.Config(
                        LOOPBACK,
                        ports[0],
                        ports[1],
                        ObserveServer.Config.DEFAULT_MAX_EXECUTIONS,
                        ObserveServer.Config.DEFAULT_MAX_EVENTS));
        CompletableFuture<Integer> status = new CompletableFuture<>();
        CompletableFuture<DeadLetterReviewState> stateAfter = new CompletableFuture<>();
        // Admission is closed but the store is still open: the window where the replay itself is refused.
        stored.subscribeSync(EngineShutdownEvent.class, e -> {
            try {
                URI uri = URI.create(
                        "http://" + LOOPBACK + ":" + storedServer.httpPort() + "/api/dead-letters/dl-1/replay");
                status.complete(client.send(
                                TestAuth.authorized(uri)
                                        .POST(HttpRequest.BodyPublishers.noBody())
                                        .build(),
                                HttpResponse.BodyHandlers.ofString())
                        .statusCode());
                stateAfter.complete(
                        store.findDeadLetterById("dl-1").orElseThrow().reviewState());
            } catch (IOException | RuntimeException ex) {
                status.completeExceptionally(ex);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                status.completeExceptionally(ex);
            }
        });
        try {
            stored.shutdown(Duration.ZERO);
        } finally {
            storedServer.close();
        }

        assertEquals(503, status.get(WAIT_SECONDS, TimeUnit.SECONDS));
        assertEquals(DeadLetterReviewState.PENDING, stateAfter.get(WAIT_SECONDS, TimeUnit.SECONDS));
    }

    private void awaitDraining() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (engine.isAcceptingExecutions()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("engine never started draining");
            }
            Thread.sleep(10);
        }
    }

    private HttpResponse<String> postExecute() throws IOException, InterruptedException {
        return client.send(
                TestAuth.authorized(uri("/api/execute"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"goal\":\"process order\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return client.send(
                HttpRequest.newBuilder(uri(path))
                        .timeout(Duration.ofSeconds(WAIT_SECONDS))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://" + LOOPBACK + ":" + server.httpPort() + path);
    }

    private static JsonNode json(HttpResponse<String> response) throws IOException {
        return JSON.readTree(response.body());
    }

    private static int[] freePorts() throws IOException {
        try (ServerSocket first = new ServerSocket(0);
                ServerSocket second = new ServerSocket(0)) {
            return new int[] {first.getLocalPort(), second.getLocalPort()};
        }
    }

    private NexoraPlugin plugin() {
        return new NexoraPlugin() {
            @Override
            public PluginDescriptor descriptor() {
                return new PluginDescriptor("observe-shutdown-test", "1.0", "observe shutdown test", List.of(), null);
            }

            @Override
            public void initialize(PluginContext ctx) {}

            @Override
            public List<CapabilityProvider> capabilityProviders() {
                return List.of(provider("slow", req -> awaitRelease()));
            }

            @Override
            public void shutdown() {}
        };
    }

    private CapabilityResult awaitRelease() {
        stepStarted.countDown();
        try {
            return stepRelease.await(WAIT_SECONDS, TimeUnit.SECONDS)
                    ? CapabilityResult.success("done")
                    : CapabilityResult.failure("NOT_RELEASED", "test never released the step");
        } catch (InterruptedException e) {
            return CapabilityResult.failure("INTERRUPTED", "interrupted by cancel");
        }
    }

    private static CapabilityProvider provider(String id, Capability capability) {
        return new CapabilityProvider() {
            @Override
            public CapabilityDescriptor descriptor() {
                return new CapabilityDescriptor(id, id, List.of(), List.of(), true, false);
            }

            @Override
            public Capability create(PluginContext ctx) {
                return capability;
            }
        };
    }
}
