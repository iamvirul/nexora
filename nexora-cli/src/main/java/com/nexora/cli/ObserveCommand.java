package com.nexora.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexora.api.NexoraEngine;
import com.nexora.api.observability.NexoraObservability;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

@Command(
        name = "observe",
        // description loaded from help/observe.help at startup via HelpLoader
        mixinStandardHelpOptions = true
)
public class ObserveCommand implements Callable<Integer> {

    private static final ObjectMapper JSON = new ObjectMapper();

    @ParentCommand
    private NexoraCli parent;

    @Option(names = {"--host"}, defaultValue = "0.0.0.0",
            description = "Host interface for observability server. Default: ${DEFAULT-VALUE}")
    private String host;

    @Option(names = {"--port"}, defaultValue = "9464",
            description = "Port for UI/API/metrics endpoints. Default: ${DEFAULT-VALUE}")
    private int port;

    @Option(names = {"--max-executions"}, defaultValue = "100",
            description = "Number of recent executions retained in UI memory. Default: ${DEFAULT-VALUE}")
    private int maxExecutions;

    @Option(names = {"--max-events"}, defaultValue = "200",
            description = "Max timeline events retained per execution. Default: ${DEFAULT-VALUE}")
    private int maxEvents;

    @Override
    public Integer call() throws Exception {
        NexoraEngine engine = parent.engine();
        NexoraObservability observability = NexoraObservability.attach(engine, maxExecutions, maxEvents);
        byte[] indexHtml = loadUiHtml();

        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

        server.createContext("/metrics", exchange -> {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendText(exchange, 405, "Method Not Allowed");
                return;
            }
            send(exchange, 200, observability.metricsContentType(),
                    observability.scrapePrometheus().getBytes(StandardCharsets.UTF_8));
        });

        server.createContext("/api/process", exchange -> {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendText(exchange, 405, "Method Not Allowed");
                return;
            }
            byte[] payload = JSON.writeValueAsBytes(observability.processSnapshot());
            send(exchange, 200, "application/json; charset=utf-8", payload);
        });

        server.createContext("/api/execute", exchange -> {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendText(exchange, 405, "Method Not Allowed");
                return;
            }

            ExecuteRequest request;
            try (InputStream body = exchange.getRequestBody()) {
                request = JSON.readValue(body, ExecuteRequest.class);
            } catch (Exception e) {
                sendJson(exchange, 400, Map.of(
                        "accepted", false,
                        "error", "Invalid JSON request: " + e.getMessage()
                ));
                return;
            }

            if (request.goal() == null || request.goal().isBlank()) {
                sendJson(exchange, 400, Map.of(
                        "accepted", false,
                        "error", "Field 'goal' is required"
                ));
                return;
            }

            Map<String, Object> context = request.context() == null ? Map.of() : request.context();
            engine.execute(request.goal(), context)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            System.err.printf("Execution failed goal=%s error=%s%n",
                                    request.goal(), ex.getMessage());
                        }
                    });

            sendJson(exchange, 202, Map.of(
                    "accepted", true,
                    "message", "Execution accepted",
                    "goal", request.goal()
            ));
        });

        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (!Objects.equals(path, "/") && !Objects.equals(path, "/index.html")) {
                sendText(exchange, 404, "Not Found");
                return;
            }
            send(exchange, 200, "text/html; charset=utf-8", indexHtml);
        });

        CountDownLatch shutdownLatch = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                server.stop(0);
                observability.close();
            } finally {
                shutdownLatch.countDown();
            }
        }));

        server.start();
        System.out.println("Nexora Observability Server");
        System.out.println("===========================");
        System.out.printf("UI:       http://%s:%d/%n", hostForDisplay(host), port);
        System.out.printf("Metrics:  http://%s:%d/metrics%n", hostForDisplay(host), port);
        System.out.printf("Process:  http://%s:%d/api/process%n", hostForDisplay(host), port);
        System.out.println();
        System.out.println("Press Ctrl+C to stop.");

        shutdownLatch.await();
        return 0;
    }

    private static String hostForDisplay(String host) {
        return "0.0.0.0".equals(host) ? "localhost" : host;
    }

    private static void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] payload = JSON.writeValueAsBytes(body);
        send(exchange, status, "application/json; charset=utf-8", payload);
    }

    private static void sendText(HttpExchange exchange, int status, String body) throws IOException {
        send(exchange, status, "text/plain; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange exchange, int status, String contentType, byte[] payload) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, payload.length);
        try (var os = exchange.getResponseBody()) {
            os.write(payload);
        } finally {
            exchange.close();
        }
    }

    private static byte[] loadUiHtml() {
        String resource = "observe/index.html";
        try (InputStream is = ObserveCommand.class.getClassLoader().getResourceAsStream(resource)) {
            if (is == null) {
                return "<h1>Missing observe/index.html resource</h1>".getBytes(StandardCharsets.UTF_8);
            }
            return is.readAllBytes();
        } catch (IOException e) {
            return ("<h1>Failed to load UI resource</h1><pre>" + e.getMessage() + "</pre>")
                    .getBytes(StandardCharsets.UTF_8);
        }
    }

    private record ExecuteRequest(String goal, Map<String, Object> context) {}
}
