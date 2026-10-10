package com.nexora.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexora.api.NexoraEngine;
import com.nexora.persistence.jdbc.JdbcExecutionStore;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HealthEndpointsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String VERSION = "9.9.9-test";
    private static final String LOOPBACK = "127.0.0.1";

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private JdbcExecutionStore store;
    private NexoraEngine engine;
    private HttpServer server;

    @BeforeEach
    void setUp() throws IOException {
        store = JdbcExecutionStore.h2InMemory();
        engine = NexoraEngine.builder().withExecutionStore(store).build();
        server = HttpServer.create(new InetSocketAddress(LOOPBACK, 0), 0);
        new HealthEndpoints(engine, VERSION).register(server);
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        engine.close();
    }

    @Test
    void liveReturns200WhenProcessIsRunning() throws Exception {
        HttpResponse<String> response = get(HealthEndpoints.LIVE_PATH);

        assertEquals(200, response.statusCode());
        assertEquals("UP", json(response).get("status").asText());
    }

    @Test
    void liveStaysUpWhenDatabaseIsUnreachable() throws Exception {
        store.close();

        assertEquals(200, get(HealthEndpoints.LIVE_PATH).statusCode());
    }

    @Test
    void readyReturns200WithAllChecksUpWhenDependenciesAreHealthy() throws Exception {
        HttpResponse<String> response = get(HealthEndpoints.READY_PATH);

        assertEquals(200, response.statusCode());
        JsonNode body = json(response);
        assertEquals("UP", body.get("status").asText());
        assertEquals("UP", body.at("/checks/persistence").asText());
        assertEquals("UP", body.at("/checks/plugins").asText());
        assertEquals("UP", body.at("/checks/executor").asText());
    }

    @Test
    void readyReturns503WhenDatabaseIsUnreachable() throws Exception {
        store.close();

        HttpResponse<String> response = get(HealthEndpoints.READY_PATH);

        assertEquals(503, response.statusCode());
        JsonNode body = json(response);
        assertEquals("DOWN", body.get("status").asText());
        assertEquals("DOWN", body.at("/checks/persistence").asText());
        assertEquals("UP", body.at("/checks/plugins").asText());
    }

    @Test
    void summaryReportsVersionChecksAndCapabilities() throws Exception {
        HttpResponse<String> response = get(HealthEndpoints.SUMMARY_PATH);

        assertEquals(200, response.statusCode());
        JsonNode body = json(response);
        assertEquals("UP", body.get("status").asText());
        assertEquals(VERSION, body.get("version").asText());
        assertEquals("UP", body.at("/checks/persistence").asText());
        assertTrue(body.get("capabilities").isArray(), "capabilities must be an array: " + body);
    }

    @Test
    void summaryReturns503WhenNotReady() throws Exception {
        store.close();

        HttpResponse<String> response = get(HealthEndpoints.SUMMARY_PATH);

        assertEquals(503, response.statusCode());
        assertEquals("DOWN", json(response).get("status").asText());
    }

    @Test
    void healthEndpointsRejectNonGetMethods() throws Exception {
        HttpRequest post = HttpRequest.newBuilder(uri(HealthEndpoints.READY_PATH))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        assertEquals(405, client.send(post, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test
    void unknownPathUnderHealthReturns404() throws Exception {
        assertEquals(404, get("/health/unknown").statusCode());
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(path)).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://" + LOOPBACK + ":" + server.getAddress().getPort() + path);
    }

    private static JsonNode json(HttpResponse<String> response) throws IOException {
        return JSON.readTree(response.body());
    }
}
