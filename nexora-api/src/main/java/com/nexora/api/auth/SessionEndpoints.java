package com.nexora.api.auth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexora.core.tenant.TenantId;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsExchange;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Login. A correct username and password mints a <em>user API key</em> ({@code nxu_...}), bound to
 * that user, valid for {@link ApiAuth#SESSION_TTL}, and accepted everywhere the server's own key is.
 *
 * <ul>
 *   <li>{@code POST /api/keys} with {@code {"username": "...", "password": "..."}} returns the key in
 *       the body, for the CLI ({@code nexora login}) and scripts to send as a Bearer header.
 *   <li>{@code POST /api/session} with the same body gives the browser dashboard the key as an
 *       HttpOnly cookie instead, so page script never sees it. With
 *       {@code Authorization: Bearer <server api key>} it opens a dashboard session for the key holder;
 *       on a multi-tenant server that request must also name the session's tenant in
 *       {@value TenantResolver#HEADER}.
 *   <li>{@code GET /api/session} reports who the cookie or Bearer user key belongs to;
 *       {@code DELETE /api/session} revokes it (logout).
 * </ul>
 *
 * <p>Every key and session is bound to a tenant: a user's to the tenant of their account.
 *
 * <p>A user key or cookie can never mint another key, or one stolen key could renew itself
 * indefinitely. Removing a user or changing their password revokes all of their keys.
 *
 * <p>A session cookie can never open another session, or one stolen cookie could renew itself
 * indefinitely. Password logins must be {@code application/json}, which a cross-site page cannot
 * send without a CORS preflight this server never grants, and must come from this server's own
 * origin when the browser names one.
 */
public final class SessionEndpoints {

    public static final String PATH = "/api/session";
    public static final String KEYS_PATH = "/api/keys";

    private static final Logger log = LoggerFactory.getLogger(SessionEndpoints.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    // Comfortably above a 32-character username plus a 128-character password, far below anything abusive.
    private static final int MAX_LOGIN_BODY_BYTES = 4_096;
    private static final int STATUS_OK = 200;
    private static final int STATUS_CREATED = 201;
    private static final int STATUS_BAD_REQUEST = 400;
    private static final int STATUS_NOT_FOUND = 404;
    private static final int STATUS_METHOD_NOT_ALLOWED = 405;
    private static final int STATUS_UNSUPPORTED_MEDIA_TYPE = 415;
    private static final int STATUS_TOO_MANY_REQUESTS = 429;

    private final ApiAuth auth;
    private final TenantResolver tenants;

    public SessionEndpoints(ApiAuth auth) {
        this(auth, TenantResolver.singleTenant());
    }

    public SessionEndpoints(ApiAuth auth, TenantResolver tenants) {
        this.auth = Objects.requireNonNull(auth, "auth");
        this.tenants = Objects.requireNonNull(tenants, "tenants");
    }

    public void register(HttpServer server) {
        server.createContext(PATH, this::handle);
        server.createContext(KEYS_PATH, this::handleKeys);
    }

    private void handleKeys(HttpExchange exchange) throws IOException {
        if (!KEYS_PATH.equals(exchange.getRequestURI().getPath())) {
            auth.protect(notFound -> Responses.send(notFound, STATUS_NOT_FOUND, Responses.TEXT, "Not Found"))
                    .handle(exchange);
            return;
        }
        ApiAuth.Request request = ApiAuth.Request.of(exchange);
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            if (!auth.authenticate(request).authenticated()) {
                ApiAuth.rejectUnauthorized(exchange);
                return;
            }
            exchange.getResponseHeaders().set("Allow", "POST");
            Responses.send(exchange, STATUS_METHOD_NOT_ALLOWED, Responses.TEXT, "Method Not Allowed");
            return;
        }
        Optional<ApiAuth.LoginResult.Accepted> accepted = passwordLogin(exchange, request);
        if (accepted.isPresent()) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("apiKey", accepted.get().session().token());
            body.put("user", accepted.get().user());
            body.put("tenantId", tenantOf(accepted.get().user()));
            body.put("expiresAt", accepted.get().session().expiresAt().toString());
            log.info("User API key issued user={} remote={}", accepted.get().user(), exchange.getRemoteAddress());
            Responses.send(exchange, STATUS_CREATED, Responses.JSON, JSON.writeValueAsString(body));
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        if (!PATH.equals(exchange.getRequestURI().getPath())) {
            // HttpServer contexts match by prefix; "/api/sessionX" must not log anyone in.
            auth.protect(notFound -> Responses.send(notFound, STATUS_NOT_FOUND, Responses.TEXT, "Not Found"))
                    .handle(exchange);
            return;
        }
        ApiAuth.Request request = ApiAuth.Request.of(exchange);
        switch (exchange.getRequestMethod().toUpperCase(Locale.ROOT)) {
            case "POST" -> login(exchange, request);
            case "GET" -> describe(exchange, request);
            case "DELETE" -> logout(exchange, request);
            default -> {
                if (!auth.authenticate(request).authenticated()) {
                    ApiAuth.rejectUnauthorized(exchange);
                    return;
                }
                exchange.getResponseHeaders().set("Allow", "GET, POST, DELETE");
                Responses.send(exchange, STATUS_METHOD_NOT_ALLOWED, Responses.TEXT, "Method Not Allowed");
            }
        }
    }

    private void login(HttpExchange exchange, ApiAuth.Request request) throws IOException {
        if (request.authorization() != null) {
            loginWithApiKey(exchange, request);
            return;
        }
        Optional<ApiAuth.LoginResult.Accepted> accepted = passwordLogin(exchange, request);
        if (accepted.isPresent()) {
            sendSession(
                    exchange,
                    accepted.get().session(),
                    accepted.get().user(),
                    tenantOf(accepted.get().user()));
        }
    }

    private void loginWithApiKey(HttpExchange exchange, ApiAuth.Request request) throws IOException {
        if (auth.authenticate(request).credential() != ApiAuth.Credential.API_KEY) {
            log.warn("Rejected dashboard login with an API key remote={}", exchange.getRemoteAddress());
            ApiAuth.rejectUnauthorized(exchange);
            return;
        }
        String tenantId = null;
        if (tenants.isMultiTenant()) {
            // The key may act for any tenant; the session it opens is pinned to the one named now.
            ApiAuth.Caller keyHolder = new ApiAuth.Caller(ApiAuth.Credential.API_KEY, ApiAuth.API_KEY_PRINCIPAL);
            switch (tenants.resolve(keyHolder, exchange.getRequestHeaders().getFirst(TenantResolver.HEADER))) {
                case TenantResolver.Resolution.Scoped scoped ->
                    tenantId = scoped.scope().tenantId();
                case TenantResolver.Resolution.Rejected rejected -> {
                    sendError(exchange, rejected.status(), rejected.code(), rejected.message());
                    return;
                }
            }
        }
        ApiAuth.Session session = tenantId == null ? auth.createSession() : auth.createSession(tenantId);
        log.info(
                "Dashboard session opened principal={} tenantId={} remote={}",
                ApiAuth.API_KEY_PRINCIPAL,
                tenantId,
                exchange.getRemoteAddress());
        sendSession(exchange, session, null, tenantId);
    }

    /** The user's tenant as reported to clients; the account was just verified, so it exists. */
    private String tenantOf(String user) {
        return auth.tenantOf(user).orElse(TenantId.DEFAULT);
    }

    /**
     * Checks the JSON username and password. Answers every failure itself (400, 401, 415, 429) and
     * returns the accepted login only on success, for the caller to deliver as a cookie or a body.
     */
    private Optional<ApiAuth.LoginResult.Accepted> passwordLogin(HttpExchange exchange, ApiAuth.Request request)
            throws IOException {
        if (request.origin() != null && !ApiAuth.isSameOrigin(request)) {
            ApiAuth.rejectUnauthorized(exchange);
            return Optional.empty();
        }
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null) {
            // Nothing offered to authenticate with: unauthorized, like any other bare request.
            ApiAuth.rejectUnauthorized(exchange);
            return Optional.empty();
        }
        if (!contentType.toLowerCase(Locale.ROOT).startsWith("application/json")) {
            sendError(
                    exchange,
                    STATUS_UNSUPPORTED_MEDIA_TYPE,
                    "UNSUPPORTED_MEDIA_TYPE",
                    "Send credentials as application/json");
            return Optional.empty();
        }
        Optional<JsonNode> body = readJson(exchange.getRequestBody());
        String username = body.map(node -> node.path("username").asText(null)).orElse(null);
        String password = body.map(node -> node.path("password").asText(null)).orElse(null);
        if (username == null || password == null) {
            sendError(
                    exchange,
                    STATUS_BAD_REQUEST,
                    "INVALID_CREDENTIALS_FORMAT",
                    "Body must be {\"username\":\"...\",\"password\":\"...\"}");
            return Optional.empty();
        }
        char[] secret = password.toCharArray();
        try {
            switch (auth.passwordLogin(username, secret, clientAddress(exchange))) {
                case ApiAuth.LoginResult.Accepted accepted -> {
                    return Optional.of(accepted);
                }
                case ApiAuth.LoginResult.Rejected() -> ApiAuth.rejectUnauthorized(exchange);
                case ApiAuth.LoginResult.Throttled throttled -> {
                    exchange.getResponseHeaders()
                            .set(
                                    "Retry-After",
                                    String.valueOf(
                                            Math.max(1, throttled.retryAfter().toSeconds())));
                    sendError(
                            exchange,
                            STATUS_TOO_MANY_REQUESTS,
                            "TOO_MANY_ATTEMPTS",
                            "Too many login attempts; try again later");
                }
            }
            return Optional.empty();
        } finally {
            Arrays.fill(secret, '\0');
        }
    }

    private void describe(HttpExchange exchange, ApiAuth.Request request) throws IOException {
        ApiAuth.Caller caller = auth.authenticate(request);
        if (!caller.authenticated()) {
            ApiAuth.rejectUnauthorized(exchange);
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        Optional<ApiAuth.SessionInfo> session = switch (caller.credential()) {
            case SESSION -> auth.currentSession(request.cookie());
            case USER_KEY -> auth.currentUserKey(request.authorization());
            default -> Optional.empty();
        };
        body.put("user", session.map(ApiAuth.SessionInfo::user).orElse(null));
        body.put("tenantId", caller.tenantId());
        body.put("multiTenant", tenants.isMultiTenant());
        body.put("credential", caller.credential().name());
        body.put("expiresAt", session.map(info -> info.expiresAt().toString()).orElse(null));
        body.put("passwordLogin", auth.passwordLoginEnabled());
        Responses.send(exchange, STATUS_OK, Responses.JSON, JSON.writeValueAsString(body));
    }

    private void logout(HttpExchange exchange, ApiAuth.Request request) throws IOException {
        ApiAuth.Caller caller = auth.authenticate(request);
        if (!caller.authenticated()) {
            ApiAuth.rejectUnauthorized(exchange);
            return;
        }
        auth.revokeSession(request.cookie());
        auth.revokeBearer(request.authorization());
        log.info("Dashboard session closed principal={} remote={}", caller.name(), exchange.getRemoteAddress());
        exchange.getResponseHeaders().add("Set-Cookie", ApiAuth.clearedSessionCookie(isHttps(exchange)));
        Responses.send(exchange, STATUS_OK, Responses.JSON, "{\"status\":\"LOGGED_OUT\"}");
    }

    /** Over HTTPS the cookie is marked Secure, so a later plain-HTTP request to the same host cannot leak it. */
    private static boolean isHttps(HttpExchange exchange) {
        return exchange instanceof HttpsExchange;
    }

    private static void sendSession(HttpExchange exchange, ApiAuth.Session session, String user, String tenantId)
            throws IOException {
        exchange.getResponseHeaders().add("Set-Cookie", ApiAuth.sessionCookie(session.token(), isHttps(exchange)));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("user", user);
        body.put("tenantId", tenantId);
        body.put("expiresAt", session.expiresAt().toString());
        Responses.send(exchange, STATUS_OK, Responses.JSON, JSON.writeValueAsString(body));
    }

    private static void sendError(HttpExchange exchange, int status, String code, String message) throws IOException {
        Responses.send(
                exchange,
                status,
                Responses.JSON,
                JSON.writeValueAsString(Map.of("error", Map.of("code", code, "message", message))));
    }

    /** Reads at most {@value #MAX_LOGIN_BODY_BYTES} bytes; anything larger or not JSON is treated as absent. */
    private static Optional<JsonNode> readJson(InputStream in) throws IOException {
        byte[] bytes = in.readNBytes(MAX_LOGIN_BODY_BYTES + 1);
        if (bytes.length > MAX_LOGIN_BODY_BYTES) {
            return Optional.empty();
        }
        try {
            JsonNode node = JSON.readTree(bytes);
            return node != null && node.isObject() ? Optional.of(node) : Optional.empty();
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
    }

    private static String clientAddress(HttpExchange exchange) {
        InetSocketAddress remote = exchange.getRemoteAddress();
        return remote != null && remote.getAddress() != null
                ? remote.getAddress().getHostAddress()
                : "unknown";
    }
}
