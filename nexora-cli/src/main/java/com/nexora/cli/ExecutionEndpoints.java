package com.nexora.cli;

import com.nexora.api.ExecutionNotFoundException;
import com.nexora.api.ExecutionNotOwnedException;
import com.nexora.api.ExecutionNotReplayableException;
import com.nexora.api.NexoraEngine;
import com.nexora.api.auth.ApiAuth;
import com.nexora.api.auth.TenantResolver;
import com.nexora.api.auth.TenantScope;
import com.nexora.core.execution.ExecutionHandle;
import com.nexora.core.execution.ExecutionStatus;
import com.nexora.core.intent.Intent;
import com.nexora.persistence.ExecutionCursor;
import com.nexora.persistence.ExecutionFilter;
import com.nexora.persistence.ExecutionRecord;
import com.nexora.persistence.ExecutionState;
import com.nexora.persistence.ExecutionStore;
import com.nexora.tracing.otel.W3CTraceparent;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Execution management endpoints on the observe server.
 *
 * <p>{@code POST /api/execute} starts an execution and returns {@code 202} with its
 * {@code executionId}, so the caller can track or cancel it.
 *
 * <p>{@code GET /api/executions} lists execution history, newest first, with cursor pagination
 * and {@code status}, {@code goal}, {@code from}, {@code to}, {@code limit} filters. Responds
 * {@code {"data": [...], "cursor": "...", "total": N}}; {@code cursor} is {@code null} on the last page.
 *
 * <p>{@code GET /api/executions/{executionId}} returns one execution with its step records.
 *
 * <p>{@code POST /api/executions/{executionId}/replay} re-runs a failed, timed-out, cancelled or
 * compensated execution: {@code 202} with the new {@code executionId}, {@code 409} for any other
 * state, {@code 404} when unknown.
 *
 * <p>{@code DELETE /api/executions/{executionId}} cancels a running execution:
 * {@code 200} when cancelled (repeat calls also return 200), {@code 409} when it already finished
 * or is owned by another engine instance, {@code 404} when unknown.
 *
 * <p>History requires a persistence store; without one those routes return {@code 503}.
 *
 * <p>Every route acts for the tenant {@link TenantResolver} assigns the request. On a multi-tenant
 * server, new executions run for that tenant and another tenant's executions answer {@code 404},
 * exactly like unknown ones.
 */
final class ExecutionEndpoints {

    static final String EXECUTE_PATH = "/api/execute";
    static final String LIST_PATH = "/api/executions";
    static final String PATH_PREFIX = LIST_PATH + "/";
    static final String REPLAY_SUFFIX = "/replay";

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
    private final TenantResolver tenants;

    ExecutionEndpoints(NexoraEngine engine) {
        this(engine, TenantResolver.singleTenant());
    }

    ExecutionEndpoints(NexoraEngine engine, TenantResolver tenants) {
        this.engine = Objects.requireNonNull(engine, "engine must not be null");
        this.tenants = Objects.requireNonNull(tenants, "tenants must not be null");
    }

    /**
     * Every route requires an authenticated caller; {@code auth} answers 401 before any handler runs,
     * and the tenant is resolved (400 or 403 on failure) before the route's own checks.
     */
    void register(HttpServer server, ApiAuth auth) {
        register(server, auth::protect);
    }

    /** @param guard authentication, then any rate limit, around each route; see {@link ObserveCommand} */
    void register(HttpServer server, UnaryOperator<HttpHandler> guard) {
        server.createContext(EXECUTE_PATH, guard.apply(ObserveCommand.scoped(tenants, this::execute)));
        server.createContext(LIST_PATH, guard.apply(ObserveCommand.scoped(tenants, this::list)));
        server.createContext(PATH_PREFIX, guard.apply(ObserveCommand.scoped(tenants, this::handle)));
    }

    private void list(HttpExchange exchange, TenantScope scope) throws IOException {
        if (!LIST_PATH.equals(exchange.getRequestURI().getPath())) {
            ObserveCommand.sendText(exchange, STATUS_NOT_FOUND, "Not Found");
            return;
        }
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", "GET");
            ObserveCommand.sendText(exchange, STATUS_METHOD_NOT_ALLOWED, "Method Not Allowed");
            return;
        }
        ExecutionStore store = engine.getStore();
        if (store == null) {
            ObserveCommand.sendJson(exchange, STATUS_UNAVAILABLE, Map.of("error", "Persistence disabled"));
            return;
        }

        ExecutionHistoryQuery query;
        try {
            query = ExecutionHistoryQuery.parse(exchange.getRequestURI().getRawQuery());
        } catch (IllegalArgumentException e) {
            ObserveCommand.sendJson(exchange, STATUS_BAD_REQUEST, Map.of("error", e.getMessage()));
            return;
        }

        try {
            ExecutionFilter filter = query.filter().forTenant(scope.tenantId());
            // The filter asks for one row more than the page so we know whether a next page exists.
            List<ExecutionRecord> rows = store.findByFilter(filter);
            boolean hasMore = rows.size() > query.pageSize();
            List<ExecutionRecord> page = hasMore ? rows.subList(0, query.pageSize()) : rows;

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("data", page.stream().map(ExecutionSummary::from).toList());
            body.put(
                    "cursor",
                    hasMore ? ExecutionCursor.after(page.get(page.size() - 1)).encode() : null);
            body.put("total", store.count(filter));
            ObserveCommand.sendJson(exchange, STATUS_OK, body);
        } catch (RuntimeException e) {
            log.error("Execution history query failed", e);
            ObserveCommand.sendJson(exchange, STATUS_INTERNAL_ERROR, Map.of("error", "Execution history query failed"));
        }
    }

    private void detail(HttpExchange exchange, TenantScope scope, String executionId) throws IOException {
        ExecutionStore store = engine.getStore();
        if (store == null) {
            ObserveCommand.sendJson(exchange, STATUS_UNAVAILABLE, Map.of("error", "Persistence disabled"));
            return;
        }
        Optional<ExecutionRecord> record;
        try {
            record = scope.tenantId() == null
                    ? store.findById(executionId)
                    : store.findById(scope.tenantId(), executionId);
        } catch (RuntimeException e) {
            log.error("Execution detail query failed executionId={}", executionId, e);
            sendError(exchange, STATUS_INTERNAL_ERROR, executionId, "Execution detail query failed");
            return;
        }
        if (record.isEmpty()) {
            sendError(exchange, STATUS_NOT_FOUND, executionId, "Execution not found");
            return;
        }
        ObserveCommand.sendJson(exchange, STATUS_OK, record.get());
    }

    private void execute(HttpExchange exchange, TenantScope scope) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", "POST");
            ObserveCommand.sendText(exchange, STATUS_METHOD_NOT_ALLOWED, "Method Not Allowed");
            return;
        }

        ExecuteRequest request;
        try (InputStream body = exchange.getRequestBody()) {
            request = ObserveCommand.JSON.readValue(body, ExecuteRequest.class);
        } catch (IOException e) {
            ObserveCommand.sendJson(
                    exchange,
                    STATUS_BAD_REQUEST,
                    Map.of("accepted", false, "error", "Invalid JSON request: " + e.getMessage()));
            return;
        }
        if (request.goal() == null || request.goal().isBlank()) {
            ObserveCommand.sendJson(
                    exchange, STATUS_BAD_REQUEST, Map.of("accepted", false, "error", "Field 'goal' is required"));
            return;
        }

        Intent intent = new Intent(
                request.goal(),
                request.context() == null ? Map.of() : request.context(),
                null,
                request.webhookUrl(),
                request.webhookEvents(),
                scope.tenantId());
        ExecutionHandle handle = W3CTraceparent.parse(
                        exchange.getRequestHeaders().getFirst("traceparent"))
                .map(parsed -> engine.submit(intent, W3CTraceparent.toTraceContext(parsed)))
                .orElseGet(() -> engine.submit(intent));
        handle.result().whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("Execution failed executionId={} goal={}", handle.executionId(), request.goal(), ex);
            }
        });

        ObserveCommand.sendJson(
                exchange,
                STATUS_ACCEPTED,
                Map.of(
                        "accepted",
                        true,
                        "message",
                        "Execution accepted",
                        "goal",
                        request.goal(),
                        "executionId",
                        handle.executionId()));
    }

    private void handle(HttpExchange exchange, TenantScope scope) throws IOException {
        String remainder = exchange.getRequestURI().getPath().substring(PATH_PREFIX.length());
        if (remainder.endsWith(REPLAY_SUFFIX)) {
            handleReplay(exchange, scope, remainder.substring(0, remainder.length() - REPLAY_SUFFIX.length()));
            return;
        }
        String executionId = remainder;
        if (executionId.isBlank() || executionId.contains("/")) {
            ObserveCommand.sendText(exchange, STATUS_NOT_FOUND, "Not Found");
            return;
        }
        switch (exchange.getRequestMethod().toUpperCase(Locale.ROOT)) {
            case "GET" -> detail(exchange, scope, executionId);
            case "DELETE" -> cancel(exchange, scope, executionId);
            default -> {
                exchange.getResponseHeaders().set("Allow", "GET, DELETE");
                ObserveCommand.sendText(exchange, STATUS_METHOD_NOT_ALLOWED, "Method Not Allowed");
            }
        }
    }

    private void handleReplay(HttpExchange exchange, TenantScope scope, String executionId) throws IOException {
        if (executionId.isBlank() || executionId.contains("/")) {
            ObserveCommand.sendText(exchange, STATUS_NOT_FOUND, "Not Found");
            return;
        }
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", "POST");
            ObserveCommand.sendText(exchange, STATUS_METHOD_NOT_ALLOWED, "Method Not Allowed");
            return;
        }
        if (engine.getStore() == null) {
            ObserveCommand.sendJson(exchange, STATUS_UNAVAILABLE, Map.of("error", "Persistence disabled"));
            return;
        }
        try {
            ExecutionHandle replay = engine.replay(scope.tenantId(), executionId);
            ObserveCommand.sendJson(
                    exchange,
                    STATUS_ACCEPTED,
                    Map.of("accepted", true, "executionId", replay.executionId(), "replayOf", executionId));
        } catch (ExecutionNotFoundException e) {
            sendError(exchange, STATUS_NOT_FOUND, executionId, "Execution not found");
        } catch (ExecutionNotReplayableException e) {
            ObserveCommand.sendJson(
                    exchange,
                    STATUS_CONFLICT,
                    Map.of(
                            "error",
                            "Only FAILED, TIMED_OUT, CANCELLED and COMPENSATED executions can be replayed",
                            "executionId",
                            executionId,
                            "state",
                            e.state()));
        } catch (RuntimeException e) {
            log.error("Replay request failed executionId={}", executionId, e);
            sendError(exchange, STATUS_INTERNAL_ERROR, executionId, "Replay request failed");
        }
    }

    private void cancel(HttpExchange exchange, TenantScope scope, String executionId) throws IOException {
        try {
            boolean cancelled =
                    engine.cancel(scope.tenantId(), executionId).get(CANCEL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
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

    /** List row: no context or steps, which can be large and are available from the detail route. */
    private record ExecutionSummary(
            String executionId,
            String traceId,
            String goal,
            ExecutionState state,
            Instant startedAt,
            Instant completedAt,
            Long durationMs) {

        static ExecutionSummary from(ExecutionRecord record) {
            Long durationMs = record.completedAt() == null
                    ? null
                    : Duration.between(record.startedAt(), record.completedAt()).toMillis();
            return new ExecutionSummary(
                    record.executionId(),
                    record.traceId(),
                    record.goal(),
                    record.state(),
                    record.startedAt(),
                    record.completedAt(),
                    durationMs);
        }
    }

    private record ExecuteRequest(
            String goal, Map<String, Object> context, String webhookUrl, List<ExecutionStatus> webhookEvents) {}
}
