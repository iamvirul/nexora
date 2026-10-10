package com.nexora.cli;

import com.nexora.api.ExecutionNotFoundException;
import com.nexora.api.ExecutionNotOwnedException;
import com.nexora.api.NexoraEngine;
import com.nexora.core.execution.ExecutionHandle;
import com.nexora.core.execution.ExecutionStatus;
import com.nexora.core.intent.Intent;
import com.nexora.tracing.otel.W3CTraceparent;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Execution management endpoints on the observe server.
 *
 * <p>{@code POST /api/execute} starts an execution and returns {@code 202} with its
 * {@code executionId}, so the caller can track or cancel it.
 *
 * <p>{@code DELETE /api/executions/{executionId}} cancels a running execution:
 * {@code 200} when cancelled (repeat calls also return 200), {@code 409} when it already finished
 * or is owned by another engine instance, {@code 404} when unknown.
 *
 * <p>Authentication will be enforced once #30 lands.
 */
final class ExecutionEndpoints {

    static final String EXECUTE_PATH = "/api/execute";
    static final String PATH_PREFIX = "/api/executions/";

    private static final Logger log = LoggerFactory.getLogger(ExecutionEndpoints.class);

    // Accepting a cancel is an in-memory flag flip or one store lookup; anything slower is a stuck store.
    private static final Duration CANCEL_TIMEOUT = Duration.ofSeconds(10);

    private static final int STATUS_OK = 200;
    private static final int STATUS_ACCEPTED = 202;
    private static final int STATUS_BAD_REQUEST = 400;
    private static final int STATUS_NOT_FOUND = 404;
    private static final int STATUS_METHOD_NOT_ALLOWED = 405;
    private static final int STATUS_CONFLICT = 409;
    private static final int STATUS_INTERNAL_ERROR = 500;
    private static final int STATUS_UNAVAILABLE = 503;

    private final NexoraEngine engine;

    ExecutionEndpoints(NexoraEngine engine) {
        this.engine = Objects.requireNonNull(engine, "engine must not be null");
    }

    void register(HttpServer server) {
        server.createContext(EXECUTE_PATH, this::execute);
        server.createContext(PATH_PREFIX, this::handle);
    }

    private void execute(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", "POST");
            ObserveCommand.sendText(exchange, STATUS_METHOD_NOT_ALLOWED, "Method Not Allowed");
            return;
        }

        ExecuteRequest request;
        try (InputStream body = exchange.getRequestBody()) {
            request = ObserveCommand.JSON.readValue(body, ExecuteRequest.class);
        } catch (IOException e) {
            ObserveCommand.sendJson(exchange, STATUS_BAD_REQUEST,
                    Map.of("accepted", false, "error", "Invalid JSON request: " + e.getMessage()));
            return;
        }
        if (request.goal() == null || request.goal().isBlank()) {
            ObserveCommand.sendJson(exchange, STATUS_BAD_REQUEST,
                    Map.of("accepted", false, "error", "Field 'goal' is required"));
            return;
        }

        Intent intent = new Intent(request.goal(),
                request.context() == null ? Map.of() : request.context(),
                null, request.webhookUrl(), request.webhookEvents());
        ExecutionHandle handle = W3CTraceparent.parse(exchange.getRequestHeaders().getFirst("traceparent"))
                .map(parsed -> engine.submit(intent, W3CTraceparent.toTraceContext(parsed)))
                .orElseGet(() -> engine.submit(intent));
        handle.result().whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("Execution failed executionId={} goal={}", handle.executionId(), request.goal(), ex);
            }
        });

        ObserveCommand.sendJson(exchange, STATUS_ACCEPTED, Map.of(
                "accepted", true,
                "message", "Execution accepted",
                "goal", request.goal(),
                "executionId", handle.executionId()));
    }

    private void handle(HttpExchange exchange) throws IOException {
        String executionId = exchange.getRequestURI().getPath().substring(PATH_PREFIX.length());
        if (executionId.isBlank() || executionId.contains("/")) {
            ObserveCommand.sendText(exchange, STATUS_NOT_FOUND, "Not Found");
            return;
        }
        if (!"DELETE".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", "DELETE");
            ObserveCommand.sendText(exchange, STATUS_METHOD_NOT_ALLOWED, "Method Not Allowed");
            return;
        }
        cancel(exchange, executionId);
    }

    private void cancel(HttpExchange exchange, String executionId) throws IOException {
        try {
            boolean cancelled = engine.cancel(executionId).get(CANCEL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (cancelled) {
                ObserveCommand.sendJson(exchange, STATUS_OK, Map.of("cancelled", true, "executionId", executionId));
            } else {
                sendError(exchange, STATUS_CONFLICT, executionId, "Execution has already finished");
            }
        } catch (ExecutionException e) {
            sendFailure(exchange, executionId, e.getCause());
        } catch (TimeoutException e) {
            log.warn("Cancel request timed out executionId={} timeout={}", executionId, CANCEL_TIMEOUT);
            sendError(exchange, STATUS_UNAVAILABLE, executionId, "Cancel request timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sendError(exchange, STATUS_UNAVAILABLE, executionId, "Cancel request interrupted");
        }
    }

    private static void sendFailure(HttpExchange exchange, String executionId, Throwable cause) throws IOException {
        if (cause instanceof ExecutionNotFoundException) {
            sendError(exchange, STATUS_NOT_FOUND, executionId, "Execution not found");
        } else if (cause instanceof ExecutionNotOwnedException) {
            sendError(exchange, STATUS_CONFLICT, executionId, "Execution is not running on this engine instance");
        } else {
            log.error("Cancel request failed executionId={}", executionId, cause);
            sendError(exchange, STATUS_INTERNAL_ERROR, executionId, "Cancel request failed");
        }
    }

    private static void sendError(HttpExchange exchange, int status, String executionId, String message)
            throws IOException {
        ObserveCommand.sendJson(exchange, status, Map.of("error", message, "executionId", executionId));
    }

    private record ExecuteRequest(
            String goal,
            Map<String, Object> context,
            String webhookUrl,
            List<ExecutionStatus> webhookEvents) {}
}
