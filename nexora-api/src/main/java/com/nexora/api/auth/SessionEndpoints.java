package com.nexora.api.auth;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.util.Locale;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dashboard login: {@code POST /api/session} trades the API key (as a Bearer header) for an
 * HttpOnly session cookie, and {@code DELETE /api/session} ends it.
 *
 * <p>Only the API key can open a session. Letting a session cookie open another would let one
 * stolen cookie renew itself indefinitely.
 */
public final class SessionEndpoints {

    public static final String PATH = "/api/session";

    private static final Logger log = LoggerFactory.getLogger(SessionEndpoints.class);
    private static final int STATUS_OK = 200;
    private static final int STATUS_NOT_FOUND = 404;
    private static final int STATUS_METHOD_NOT_ALLOWED = 405;

    private final ApiAuth auth;

    public SessionEndpoints(ApiAuth auth) {
        this.auth = Objects.requireNonNull(auth, "auth");
    }

    public void register(HttpServer server) {
        server.createContext(PATH, this::handle);
    }

    private void handle(HttpExchange exchange) throws IOException {
        if (!PATH.equals(exchange.getRequestURI().getPath())) {
            // HttpServer contexts match by prefix; "/api/sessionX" must not log anyone in.
            auth.protect(notFound -> Responses.send(notFound, STATUS_NOT_FOUND, Responses.TEXT, "Not Found"))
                    .handle(exchange);
            return;
        }
        ApiAuth.Credential credential = auth.authenticate(ApiAuth.Request.of(exchange));
        switch (exchange.getRequestMethod().toUpperCase(Locale.ROOT)) {
            case "POST" -> login(exchange, credential);
            case "DELETE" -> logout(exchange, credential);
            default -> {
                if (credential == ApiAuth.Credential.NONE) {
                    ApiAuth.rejectUnauthorized(exchange);
                    return;
                }
                exchange.getResponseHeaders().set("Allow", "POST, DELETE");
                Responses.send(exchange, STATUS_METHOD_NOT_ALLOWED, Responses.TEXT, "Method Not Allowed");
            }
        }
    }

    private void login(HttpExchange exchange, ApiAuth.Credential credential) throws IOException {
        if (credential != ApiAuth.Credential.API_KEY) {
            log.warn("Rejected dashboard login remote={}", exchange.getRemoteAddress());
            ApiAuth.rejectUnauthorized(exchange);
            return;
        }
        ApiAuth.Session session = auth.createSession();
        log.info("Dashboard session opened remote={} expiresAt={}", exchange.getRemoteAddress(), session.expiresAt());
        exchange.getResponseHeaders().add("Set-Cookie", ApiAuth.sessionCookie(session.token()));
        Responses.send(exchange, STATUS_OK, Responses.JSON, "{\"expiresAt\":\"" + session.expiresAt() + "\"}");
    }

    private void logout(HttpExchange exchange, ApiAuth.Credential credential) throws IOException {
        if (credential == ApiAuth.Credential.NONE) {
            ApiAuth.rejectUnauthorized(exchange);
            return;
        }
        auth.revokeSession(exchange.getRequestHeaders().getFirst("Cookie"));
        log.info("Dashboard session closed remote={}", exchange.getRemoteAddress());
        exchange.getResponseHeaders().add("Set-Cookie", ApiAuth.clearedSessionCookie());
        Responses.send(exchange, STATUS_OK, Responses.JSON, "{\"status\":\"LOGGED_OUT\"}");
    }
}
