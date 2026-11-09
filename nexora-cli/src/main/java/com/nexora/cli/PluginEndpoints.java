package com.nexora.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexora.api.NexoraEngine;
import com.nexora.api.auth.ApiAuth;
import com.nexora.loader.PluginReloadResult;
import com.nexora.spi.PluginInitializationException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Plugin management on the observe server.
 *
 * <p>{@code POST /api/plugins/{pluginId}/reload} with {@code {"jar": "/path/to/plugin.jar"}} swaps
 * an active JAR plugin for a new version without restarting (see {@link NexoraEngine#reloadPlugin}).
 * Reloading runs code from the JAR inside the server, so:
 *
 * <ul>
 *   <li>only the server's own API key may do it; user keys and dashboard sessions get {@code 403},
 *       whatever their tenant, since a plugin serves every tenant;
 *   <li>the JAR path is a path on the server, and must name a {@code .jar} file in the same
 *       directory as the plugin's current JAR, which the operator already trusts with plugin code.
 * </ul>
 *
 * <p>Responds {@code 200} with both versions, {@code 404} for a plugin that is not active,
 * {@code 409} for a plugin registered in code, {@code 422} when the new version failed and the old one
 * is still serving, and {@code 503} when the server runs without a persistence store.
 */
final class PluginEndpoints {

    static final String PATH_PREFIX = "/api/plugins/";
    static final String RELOAD_SUFFIX = "/reload";
    static final String JAR_FIELD = "jar";

    private static final Logger log = LoggerFactory.getLogger(PluginEndpoints.class);

    /** A JSON object with one path in it; anything larger is not a reload request. */
    private static final int MAX_BODY_BYTES = 4_096;

    private static final String JAR_EXTENSION = ".jar";

    private static final int STATUS_OK = 200;
    private static final int STATUS_BAD_REQUEST = 400;
    private static final int STATUS_FORBIDDEN = 403;
    private static final int STATUS_NOT_FOUND = 404;
    private static final int STATUS_METHOD_NOT_ALLOWED = 405;
    private static final int STATUS_CONFLICT = 409;
    private static final int STATUS_UNPROCESSABLE = 422;
    private static final int STATUS_INTERNAL_ERROR = 500;
    private static final int STATUS_UNAVAILABLE = 503;

    private final NexoraEngine engine;

    PluginEndpoints(NexoraEngine engine) {
        this.engine = Objects.requireNonNull(engine, "engine must not be null");
    }

    /** @param guard authentication, then any rate limit, around each route; see {@link ObserveCommand} */
    void register(HttpServer server, UnaryOperator<HttpHandler> guard) {
        server.createContext(PATH_PREFIX, guard.apply(this::handle));
    }

    private void handle(HttpExchange exchange) throws IOException {
        String remainder = exchange.getRequestURI().getPath().substring(PATH_PREFIX.length());
        if (!remainder.endsWith(RELOAD_SUFFIX)) {
            ObserveCommand.sendText(exchange, STATUS_NOT_FOUND, "Not Found");
            return;
        }
        String pluginId = remainder.substring(0, remainder.length() - RELOAD_SUFFIX.length());
        if (pluginId.isBlank() || pluginId.contains("/")) {
            ObserveCommand.sendText(exchange, STATUS_NOT_FOUND, "Not Found");
            return;
        }
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", "POST");
            ObserveCommand.sendText(exchange, STATUS_METHOD_NOT_ALLOWED, "Method Not Allowed");
            return;
        }
        ApiAuth.Caller caller = (ApiAuth.Caller) exchange.getAttribute(ApiAuth.CALLER_ATTRIBUTE);
        if (caller == null || caller.credential() != ApiAuth.Credential.API_KEY) {
            log.warn(
                    "Rejected plugin reload without the server API key principal={} pluginId={} remote={}",
                    caller == null ? null : caller.name(),
                    pluginId,
                    exchange.getRemoteAddress());
            sendError(
                    exchange,
                    STATUS_FORBIDDEN,
                    "FORBIDDEN",
                    "Reloading a plugin requires the server API key; user keys and sessions cannot do it");
            return;
        }
        reload(exchange, caller, pluginId);
    }

    private void reload(HttpExchange exchange, ApiAuth.Caller caller, String pluginId) throws IOException {
        if (engine.getStore() == null) {
            sendError(
                    exchange,
                    STATUS_UNAVAILABLE,
                    "PERSISTENCE_DISABLED",
                    "Plugin hot reload requires a persistence store; set executionStore in nexora.json or NEXORA_JDBC_URL");
            return;
        }
        if (!engine.activePluginIds().contains(pluginId)) {
            sendError(exchange, STATUS_NOT_FOUND, "PLUGIN_NOT_FOUND", "No active plugin with id " + pluginId);
            return;
        }
        Optional<Path> currentJar = engine.pluginJar(pluginId);
        if (currentJar.isEmpty()) {
            sendError(
                    exchange,
                    STATUS_CONFLICT,
                    "PLUGIN_NOT_RELOADABLE",
                    "Plugin " + pluginId + " was registered in code, not loaded from a JAR; restart to change it");
            return;
        }

        Path newJar;
        try {
            newJar = allowedJar(readJarField(exchange), currentJar.get());
        } catch (RequestRejected e) {
            sendError(exchange, STATUS_BAD_REQUEST, e.code, e.getMessage());
            return;
        }

        log.info(
                "Plugin reload requested principal={} pluginId={} jar={} remote={}",
                caller.name(),
                pluginId,
                newJar,
                exchange.getRemoteAddress());
        try {
            PluginReloadResult result = engine.reloadPlugin(pluginId, newJar);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("reloaded", true);
            body.put("pluginId", result.pluginId());
            body.put("previousVersion", result.previousVersion());
            body.put("newVersion", result.newVersion());
            ObserveCommand.sendJson(exchange, STATUS_OK, body);
        } catch (PluginInitializationException e) {
            sendError(exchange, STATUS_UNPROCESSABLE, "PLUGIN_RELOAD_FAILED", e.getMessage());
        } catch (IllegalStateException e) {
            // Deactivated or reloaded into another state between the checks above and the swap.
            sendError(exchange, STATUS_CONFLICT, "PLUGIN_NOT_RELOADABLE", e.getMessage());
        } catch (RuntimeException e) {
            log.error("Plugin reload failed unexpectedly pluginId={} jar={}", pluginId, newJar, e);
            sendError(exchange, STATUS_INTERNAL_ERROR, "INTERNAL_ERROR", "Plugin reload failed");
        }
    }

    private static String readJarField(HttpExchange exchange) throws IOException, RequestRejected {
        byte[] raw;
        try (InputStream body = exchange.getRequestBody()) {
            raw = body.readNBytes(MAX_BODY_BYTES + 1);
        }
        if (raw.length > MAX_BODY_BYTES) {
            throw new RequestRejected("INVALID_REQUEST", "Request body is over " + MAX_BODY_BYTES + " bytes");
        }
        JsonNode jar;
        try {
            jar = ObserveCommand.JSON.readTree(raw).path(JAR_FIELD);
        } catch (IOException e) {
            throw new RequestRejected("INVALID_REQUEST", "Body must be JSON: {\"jar\": \"/path/to/plugin.jar\"}");
        }
        if (!jar.isTextual() || jar.asText().isBlank()) {
            throw new RequestRejected("INVALID_REQUEST", "Field 'jar' is required: the new JAR's path on the server");
        }
        return jar.asText();
    }

    /**
     * The requested JAR, resolved to its real path, provided it is a readable {@code .jar} file in
     * the same directory as {@code currentJar}. Symlinks are followed on both sides first, so a link
     * in the plugin directory cannot point the server at a JAR elsewhere.
     */
    private static Path allowedJar(String requested, Path currentJar) throws RequestRejected {
        Path path;
        try {
            path = Path.of(requested);
        } catch (InvalidPathException e) {
            throw new RequestRejected("INVALID_JAR_PATH", "Not a valid path: " + requested);
        }
        if (!path.isAbsolute()) {
            throw new RequestRejected("INVALID_JAR_PATH", "The jar path must be absolute on the server: " + requested);
        }
        Path real;
        try {
            real = path.toRealPath();
        } catch (IOException e) {
            throw new RequestRejected("INVALID_JAR_PATH", "No such file on the server: " + requested);
        }
        Path allowedDirectory = realDirectoryOf(currentJar);
        if (!Files.isRegularFile(real)
                || !real.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(JAR_EXTENSION)) {
            throw new RequestRejected("INVALID_JAR_PATH", "Not a .jar file: " + requested);
        }
        if (allowedDirectory == null || !allowedDirectory.equals(real.getParent())) {
            throw new RequestRejected(
                    "JAR_NOT_ALLOWED",
                    "The new jar must be in the same directory as the plugin's current jar (" + allowedDirectory + ")");
        }
        return real;
    }

    /** The current JAR's directory; the JAR itself may already have been deleted or replaced. */
    private static Path realDirectoryOf(Path currentJar) throws RequestRejected {
        Path directory = currentJar.toAbsolutePath().getParent();
        try {
            return directory == null ? null : directory.toRealPath();
        } catch (IOException e) {
            throw new RequestRejected(
                    "JAR_NOT_ALLOWED", "The plugin's current jar directory no longer exists: " + directory);
        }
    }

    private static void sendError(HttpExchange exchange, int status, String code, String message) throws IOException {
        ObserveCommand.sendJson(exchange, status, Map.of("error", Map.of("code", code, "message", message)));
    }

    /** A request the route refuses before touching the engine; carries the error code to send. */
    private static final class RequestRejected extends Exception {
        private final String code;

        RequestRejected(String code, String message) {
            super(message, null, false, false);
            this.code = code;
        }
    }
}
