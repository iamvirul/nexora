package com.nexora.planner.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * A local stand-in for the Anthropic Messages API and the OpenAI Chat Completions API, so the real
 * SDK clients run against it with no network access and no API key. Each request gets the next
 * queued reply; every request is recorded for assertions.
 */
final class FakeLlmServer implements AutoCloseable {

    static final String ANTHROPIC_PATH = "/v1/messages";
    static final String OPENAI_PATH = "/v1/chat/completions";
    static final String OPENROUTER_PATH = "/api/v1/chat/completions";

    private static final ObjectMapper JSON = new ObjectMapper();

    record Reply(int status, String body) {}

    record Recorded(String path, Map<String, List<String>> headers, JsonNode body) {}

    private final HttpServer server;
    private final Deque<Reply> replies = new ArrayDeque<>();
    private final List<Recorded> requests = new ArrayList<>();

    FakeLlmServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    /** Base URL for the Anthropic and OpenAI SDKs. */
    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Base URL shaped like OpenRouter's, which carries an {@code /api/v1} prefix. */
    String openRouterBaseUrl() {
        return baseUrl() + "/api/v1";
    }

    synchronized FakeLlmServer reply(int status, String body) {
        replies.add(new Reply(status, body));
        return this;
    }

    synchronized FakeLlmServer anthropicAnswer(String text, String stopReason) {
        return reply(200, anthropicMessage(text, stopReason));
    }

    synchronized FakeLlmServer openAiAnswer(String content, String finishReason, String refusal) {
        return reply(200, openAiCompletion(content, finishReason, refusal));
    }

    synchronized List<Recorded> requests() {
        return List.copyOf(requests);
    }

    synchronized Recorded lastRequest() {
        return requests.get(requests.size() - 1);
    }

    private void handle(HttpExchange exchange) throws IOException {
        Reply reply;
        synchronized (this) {
            byte[] body = exchange.getRequestBody().readAllBytes();
            requests.add(new Recorded(
                    exchange.getRequestURI().getPath(),
                    Map.copyOf(exchange.getRequestHeaders()),
                    body.length == 0 ? JSON.createObjectNode() : JSON.readTree(body)));
            reply = replies.isEmpty() ? new Reply(500, anthropicError("api_error", "no reply queued")) : replies.poll();
        }
        byte[] payload = reply.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        // Keeps SDK retry back-off short in tests.
        exchange.getResponseHeaders().set("retry-after-ms", "1");
        exchange.sendResponseHeaders(reply.status(), payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }

    static String anthropicMessage(String text, String stopReason) {
        Map<String, Object> message = Map.of(
                "id", "msg_test",
                "type", "message",
                "role", "assistant",
                "model", "claude-opus-5-5",
                "content", List.of(Map.of("type", "text", "text", text)),
                "stop_reason", stopReason,
                "usage", Map.of("input_tokens", 120, "output_tokens", 80));
        return write(message);
    }

    static String anthropicError(String type, String message) {
        return write(Map.of("type", "error", "error", Map.of("type", type, "message", message)));
    }

    static String openAiCompletion(String content, String finishReason, String refusal) {
        Map<String, Object> messageBody = new java.util.HashMap<>();
        messageBody.put("role", "assistant");
        messageBody.put("content", content);
        messageBody.put("refusal", refusal);
        Map<String, Object> choice = new java.util.HashMap<>();
        choice.put("index", 0);
        choice.put("message", messageBody);
        choice.put("finish_reason", finishReason);
        choice.put("logprobs", null);
        return write(Map.of(
                "id",
                "chatcmpl-test",
                "object",
                "chat.completion",
                "created",
                1_700_000_000,
                "model",
                "test-model",
                "choices",
                List.of(choice),
                "usage",
                Map.of("prompt_tokens", 120, "completion_tokens", 80, "total_tokens", 200)));
    }

    synchronized FakeLlmServer geminiAnswer(String text, String finishReason) {
        return reply(200, geminiResponse(text, finishReason));
    }

    static String geminiResponse(String text, String finishReason) {
        Map<String, Object> candidate = Map.of(
                "content",
                Map.of("role", "model", "parts", List.of(Map.of("text", text))),
                "finishReason",
                finishReason,
                "index",
                0);
        return write(Map.of(
                "candidates", List.of(candidate),
                "usageMetadata", Map.of("promptTokenCount", 120, "candidatesTokenCount", 80, "totalTokenCount", 200),
                "modelVersion", "gemini-test"));
    }

    static String geminiBlocked(String blockReason) {
        return write(Map.of("promptFeedback", Map.of("blockReason", blockReason)));
    }

    static String geminiError(int code, String status, String message) {
        return write(Map.of("error", Map.of("code", code, "message", message, "status", status)));
    }

    static String openAiError(String message) {
        return write(Map.of("error", Map.of("message", message, "type", "invalid_request_error")));
    }

    private static String write(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
