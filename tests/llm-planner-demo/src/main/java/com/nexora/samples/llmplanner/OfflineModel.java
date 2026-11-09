package com.nexora.samples.llmplanner;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * A stand-in for the Anthropic Messages API on localhost, so {@code --offline} shows the LLM
 * planner without a key or network. It answers every request with the plan a model would give for
 * the demo goal, and points the plugin at itself through {@code nexora.llm.*} system properties.
 * The real Anthropic SDK inside the plugin JAR makes the call; only the server is fake.
 */
final class OfflineModel implements AutoCloseable {

    /** Validate and score the order in parallel, then charge, then email a receipt for the charge. */
    static final String CANNED_PLAN = """
            {"steps": [
              {"id": "validate", "capabilityId": "validate_order", "dependsOn": [],
               "inputs": [{"name": "orderId", "source": "context", "value": "orderId", "stepId": null, "field": null}]},
              {"id": "fraud", "capabilityId": "fraud_check", "dependsOn": [],
               "inputs": [{"name": "orderId", "source": "context", "value": "orderId", "stepId": null, "field": null}]},
              {"id": "charge", "capabilityId": "charge_card", "dependsOn": ["validate", "fraud"],
               "inputs": [{"name": "orderId", "source": "context", "value": "orderId", "stepId": null, "field": null},
                          {"name": "amount", "source": "context", "value": "amount", "stepId": null, "field": null}]},
              {"id": "receipt", "capabilityId": "send_receipt", "dependsOn": ["charge"],
               "inputs": [{"name": "orderId", "source": "context", "value": "orderId", "stepId": null, "field": null},
                          {"name": "email", "source": "context", "value": "email", "stepId": null, "field": null},
                          {"name": "chargeId", "source": "step", "value": null, "stepId": "charge", "field": "chargeId"}]}
            ]}""";

    private static final List<String> PROPERTIES =
            List.of("nexora.llm.provider", "nexora.llm.baseUrl", "nexora.llm.apiKeyFile", "nexora.llm.maxRetries");

    private final HttpServer server;
    private final Path keyFile;

    private OfflineModel(HttpServer server, Path keyFile) {
        this.server = server;
        this.keyFile = keyFile;
    }

    static OfflineModel start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", OfflineModel::answer);
        server.start();
        Path keyFile = Files.createTempFile("nexora-demo-key", ".txt");
        Files.writeString(keyFile, "offline-demo-key");
        System.setProperty("nexora.llm.provider", "anthropic");
        System.setProperty(
                "nexora.llm.baseUrl", "http://127.0.0.1:" + server.getAddress().getPort());
        System.setProperty("nexora.llm.apiKeyFile", keyFile.toString());
        System.setProperty("nexora.llm.maxRetries", "0");
        return new OfflineModel(server, keyFile);
    }

    private static void answer(HttpExchange exchange) throws IOException {
        exchange.getRequestBody().readAllBytes();
        String text = CANNED_PLAN.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
        byte[] body = ("{\"id\": \"msg_offline\", \"type\": \"message\", \"role\": \"assistant\","
                        + " \"model\": \"claude-opus-5-5\", \"stop_reason\": \"end_turn\","
                        + " \"usage\": {\"input_tokens\": 0, \"output_tokens\": 0},"
                        + " \"content\": [{\"type\": \"text\", \"text\": \"" + text + "\"}]}")
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    @Override
    public void close() {
        server.stop(0);
        PROPERTIES.forEach(System::clearProperty);
        try {
            Files.deleteIfExists(keyFile);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not delete the demo key file " + keyFile, e);
        }
    }
}
