package com.nexora.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.nexora.api.NexoraEngine;
import com.nexora.api.observability.NexoraObservability;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Keeps openapi.yaml in step with the server. Fails when a route is registered without being
 * documented, when the document describes a route the server does not serve, or when an operation
 * lacks a success example or an error example.
 */
class OpenApiSpecTest {

    private static final Set<String> HTTP_METHODS = Set.of("get", "put", "post", "delete", "patch", "head", "options");

    private JsonNode spec;
    private NexoraEngine engine;
    private NexoraObservability observability;

    @BeforeEach
    void setUp() throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("openapi.yaml")) {
            spec = new ObjectMapper(new YAMLFactory()).readTree(in);
        }
        engine = NexoraEngine.builder().build();
        observability = NexoraObservability.attach(engine);
    }

    @AfterEach
    void tearDown() {
        observability.close();
        engine.close();
    }

    @Test
    void documentIsOpenApi31() {
        assertTrue(
                spec.path("openapi").asText().startsWith("3.1."),
                spec.path("openapi").asText());
    }

    @Test
    void everyRegisteredRouteIsDocumented() {
        List<String> specPaths = specPaths();
        List<String> undocumented = registeredRoutes().stream()
                .filter(route -> specPaths.stream().noneMatch(path -> serves(route, path)))
                .toList();

        assertEquals(List.of(), undocumented, "Routes missing from openapi.yaml");
    }

    @Test
    void everyDocumentedPathIsServed() {
        List<String> routes = registeredRoutes();
        List<String> unserved = specPaths().stream()
                .filter(path -> routes.stream().noneMatch(route -> serves(route, path)))
                .toList();

        assertEquals(List.of(), unserved, "openapi.yaml documents paths the server does not register");
    }

    @Test
    void everyOperationHasASuccessExampleAndAnErrorExample() {
        List<String> missing = new ArrayList<>();
        spec.path("paths")
                .fields()
                .forEachRemaining(path -> path.getValue().fields().forEachRemaining(op -> {
                    if (!HTTP_METHODS.contains(op.getKey())) return;
                    String name = op.getKey().toUpperCase() + " " + path.getKey();
                    JsonNode responses = op.getValue().path("responses");
                    if (!hasExampleFor(responses, true)) missing.add(name + ": no 2xx example");
                    if (!hasExampleFor(responses, false)) missing.add(name + ": no 4xx/5xx example");
                }));

        assertEquals(List.of(), missing);
    }

    /** A route serves a spec path if it is that path, or a prefix context ("/api/x/") under which it lives. */
    private static boolean serves(String route, String specPath) {
        if (route.equals(specPath)) return true;
        // "/" only serves the dashboard itself, not everything below it.
        return route.endsWith("/")
                && route.length() > 1
                && specPath.startsWith(route)
                && specPath.length() > route.length();
    }

    private boolean hasExampleFor(JsonNode responses, boolean success) {
        Iterator<Map.Entry<String, JsonNode>> it = responses.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> entry = it.next();
            boolean isSuccess = entry.getKey().startsWith("2");
            if (isSuccess != success) continue;
            JsonNode content = resolve(entry.getValue()).path("content");
            Iterator<JsonNode> media = content.elements();
            while (media.hasNext()) {
                JsonNode type = media.next();
                if (type.path("examples").size() > 0 || type.has("example")) return true;
            }
        }
        return false;
    }

    private JsonNode resolve(JsonNode node) {
        String ref = node.path("$ref").asText("");
        if (ref.isEmpty()) return node;
        JsonNode target = spec;
        for (String part : ref.substring(2).split("/")) {
            target = target.path(part);
        }
        return target;
    }

    private List<String> specPaths() {
        List<String> paths = new ArrayList<>();
        spec.path("paths").fieldNames().forEachRemaining(paths::add);
        return paths;
    }

    private List<String> registeredRoutes() {
        RecordingHttpServer server = new RecordingHttpServer();
        ObserveCommand.registerRoutes(server, engine, observability, new byte[0]);
        return server.contexts;
    }

    /** Records the routes {@code registerRoutes} creates, without binding a port. */
    private static final class RecordingHttpServer extends HttpServer {
        private final List<String> contexts = new ArrayList<>();

        @Override
        public HttpContext createContext(String path, HttpHandler handler) {
            contexts.add(path);
            return null;
        }

        @Override
        public HttpContext createContext(String path) {
            contexts.add(path);
            return null;
        }

        @Override
        public void bind(InetSocketAddress addr, int backlog) {}

        @Override
        public void start() {}

        @Override
        public void setExecutor(Executor executor) {}

        @Override
        public Executor getExecutor() {
            return null;
        }

        @Override
        public void stop(int delay) {}

        @Override
        public void removeContext(String path) {}

        @Override
        public void removeContext(HttpContext context) {}

        @Override
        public InetSocketAddress getAddress() {
            return null;
        }
    }
}
