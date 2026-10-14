package com.nexora.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.nexora.api.NexoraEngine;
import com.nexora.api.observability.NexoraObservability;
import com.nexora.persistence.jdbc.JdbcExecutionStore;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Keeps openapi.yaml in step with the server. Fails when a route is registered without being
 * documented, when the document describes a route the server does not serve, or when an operation
 * lacks a success example or an error example.
 */
class OpenApiSpecTest {

    private static final List<String> PROBE_METHODS = List.of("GET", "POST", "PUT", "DELETE", "PATCH");
    private static final String LOOPBACK = "127.0.0.1";
    private static final int STATUS_METHOD_NOT_ALLOWED = 405;

    private final HttpClient client = HttpClient.newHttpClient();

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
        for (Map.Entry<String, JsonNode> path : spec.path("paths").properties()) {
            for (Map.Entry<String, JsonNode> op : path.getValue().properties()) {
                if (!HTTP_METHODS.contains(op.getKey())) continue;
                String name = op.getKey().toUpperCase() + " " + path.getKey();
                JsonNode responses = op.getValue().path("responses");
                if (!hasExampleFor(responses, true)) missing.add(name + ": no 2xx example");
                if (!hasExampleFor(responses, false)) missing.add(name + ": no 4xx/5xx example");
            }
        }

        assertEquals(List.of(), missing);
    }

    /**
     * Paths alone are not enough: a new method on an existing path (say PUT on /api/schedules) would
     * slip through. Every documented path is probed with each method on a live server; anything but
     * 405 counts as accepted and must match the documented operations exactly. Probes send empty
     * bodies and unknown ids, so they have no side effects.
     */
    @Test
    void everyPathAcceptsExactlyTheDocumentedMethods() throws Exception {
        try (NexoraEngine withStore = NexoraEngine.builder()
                        .withExecutionStore(JdbcExecutionStore.h2InMemory())
                        .build();
                NexoraObservability liveObservability = NexoraObservability.attach(withStore)) {
            HttpServer server = HttpServer.create(new InetSocketAddress(LOOPBACK, 0), 0);
            ObserveCommand.registerRoutes(server, withStore, liveObservability, new byte[0], TestAuth.auth());
            server.start();
            try {
                List<String> mismatches = new ArrayList<>();
                for (Map.Entry<String, JsonNode> path : spec.path("paths").properties()) {
                    Set<String> documented = path.getValue().properties().stream()
                            .map(Map.Entry::getKey)
                            .filter(HTTP_METHODS::contains)
                            .map(method -> method.toUpperCase(Locale.ROOT))
                            .collect(Collectors.toCollection(TreeSet::new));
                    Set<String> accepted = new TreeSet<>();
                    for (String method : PROBE_METHODS) {
                        if (probe(server, concrete(path.getKey()), method) != STATUS_METHOD_NOT_ALLOWED) {
                            accepted.add(method);
                        }
                    }
                    if (!accepted.equals(documented)) {
                        mismatches.add(path.getKey() + " documents " + documented + " but accepts " + accepted);
                    }
                }
                assertEquals(List.of(), mismatches);
            } finally {
                server.stop(0);
            }
        }
    }

    private int probe(HttpServer server, String path, String method) throws Exception {
        URI uri = URI.create("http://" + LOOPBACK + ":" + server.getAddress().getPort() + path);
        HttpRequest request = TestAuth.authorized(uri)
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build();
        return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    /** Fills path templates with values that exist ({asset}) or are safely unknown (ids). */
    private static String concrete(String specPath) {
        return specPath.replace("{asset}", "swagger-ui.css").replaceAll("\\{[^}]+}", "probe-id");
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
        for (Map.Entry<String, JsonNode> entry : responses.properties()) {
            boolean isSuccess = entry.getKey().startsWith("2");
            if (isSuccess != success) continue;
            for (Map.Entry<String, JsonNode> media :
                    resolve(entry.getValue()).path("content").properties()) {
                JsonNode type = media.getValue();
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
        spec.path("paths").properties().forEach(entry -> paths.add(entry.getKey()));
        return paths;
    }

    private List<String> registeredRoutes() {
        RecordingHttpServer server = new RecordingHttpServer();
        ObserveCommand.registerRoutes(server, engine, observability, new byte[0], TestAuth.auth());
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
