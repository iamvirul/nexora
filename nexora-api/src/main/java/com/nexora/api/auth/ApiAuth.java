package com.nexora.api.auth;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.net.URI;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Guards the observe server's {@code /api} routes and its WebSocket stream.
 *
 * <p>Two credentials are accepted. {@code Authorization: Bearer <api key>} works for any client.
 * The dashboard instead trades the key once for an HttpOnly session cookie (see
 * {@link SessionEndpoints}), so the key itself never sits in browser storage where injected script
 * could read it.
 *
 * <p>The cookie is {@code SameSite=Strict}, but SameSite ignores ports: a page served from another
 * port on the same host is "same site" and would still send it. Cookie-authenticated requests that
 * change state, and WebSocket handshakes, must therefore also carry an {@code Origin} on this
 * server's own host and port.
 */
public final class ApiAuth {

    public static final String SESSION_COOKIE = "nexora_session";
    public static final Duration SESSION_TTL = Duration.ofHours(12);
    public static final int MAX_SESSIONS = 1_000;

    private static final Logger log = LoggerFactory.getLogger(ApiAuth.class);
    private static final String BEARER_SCHEME = "bearer";
    private static final int SESSION_TOKEN_BYTES = 32;
    // Base64url of 32 bytes without padding; anything else cannot be a token this server issued.
    private static final Pattern SESSION_TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    private static final int STATUS_UNAUTHORIZED = 401;
    private static final String UNAUTHORIZED_BODY = "{\"error\":{\"code\":\"UNAUTHORIZED\","
            + "\"message\":\"A valid API key is required: send Authorization: Bearer <key>\"}}";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();

    /** How a request proved who it is. */
    public enum Credential {
        API_KEY,
        SESSION,
        NONE
    }

    /**
     * The parts of a request that carry credentials. {@code host} is the authority the client
     * addressed (Host header); {@code origin} is the page that sent it, when a browser says so.
     */
    public record Request(String method, String authorization, String cookie, String origin, String host) {

        public static Request of(HttpExchange exchange) {
            Headers headers = exchange.getRequestHeaders();
            return new Request(
                    exchange.getRequestMethod(),
                    headers.getFirst("Authorization"),
                    headers.getFirst("Cookie"),
                    headers.getFirst("Origin"),
                    headers.getFirst("Host"));
        }
    }

    private final byte[] keyDigest;
    private final Clock clock;
    /** SHA-256 hex of each live session token, mapped to its expiry. Tokens themselves are never kept. */
    private final Map<String, Instant> sessions = new ConcurrentHashMap<>();

    public ApiAuth(byte[] keyDigest, Clock clock) {
        this.keyDigest = Objects.requireNonNull(keyDigest, "keyDigest").clone();
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public ApiAuth(byte[] keyDigest) {
        this(keyDigest, Clock.systemUTC());
    }

    /** Wraps {@code handler} so it only runs for an authenticated request; others get 401 first. */
    public HttpHandler protect(HttpHandler handler) {
        Objects.requireNonNull(handler, "handler");
        return exchange -> {
            Credential credential = authenticate(Request.of(exchange));
            if (credential == Credential.NONE) {
                log.warn(
                        "Rejected unauthenticated API request method={} path={} remote={}",
                        exchange.getRequestMethod(),
                        exchange.getRequestURI().getPath(),
                        exchange.getRemoteAddress());
                rejectUnauthorized(exchange);
                return;
            }
            if (!SAFE_METHODS.contains(exchange.getRequestMethod().toUpperCase(Locale.ROOT))) {
                // Audit trail for state changes: there is one key, so the credential type is the "who".
                log.info(
                        "Authorized API request method={} path={} credential={} remote={}",
                        exchange.getRequestMethod(),
                        exchange.getRequestURI().getPath(),
                        credential,
                        exchange.getRemoteAddress());
            }
            handler.handle(exchange);
        };
    }

    /** Bearer key first; a session cookie only counts when the Origin rule above is met. */
    public Credential authenticate(Request request) {
        if (bearerToken(request.authorization()).filter(this::isApiKey).isPresent()) {
            return Credential.API_KEY;
        }
        boolean needsOrigin = !SAFE_METHODS.contains(request.method().toUpperCase(Locale.ROOT));
        if (hasLiveSession(request.cookie()) && (!needsOrigin || sameAuthority(request.origin(), request.host()))) {
            return Credential.SESSION;
        }
        return Credential.NONE;
    }

    /**
     * Admits a WebSocket handshake on {@code bearer} or on a session cookie sent from the dashboard,
     * which lives on {@code dashboardPort} of the host the socket was opened on.
     */
    public boolean allowsWebSocket(String authorization, String cookie, String origin, String host, int dashboardPort) {
        if (bearerToken(authorization).filter(this::isApiKey).isPresent()) {
            return true;
        }
        return hasLiveSession(cookie) && originIsDashboard(origin, host, dashboardPort);
    }

    /** Compares digests, not keys: equal-length inputs to {@link MessageDigest#isEqual}, so timing reveals nothing. */
    public boolean isApiKey(String presented) {
        return MessageDigest.isEqual(ApiKeyStore.digest(presented), keyDigest);
    }

    /** A newly issued session: the token to set as the cookie, and when it stops being accepted. */
    public record Session(String token, Instant expiresAt) {}

    /** Issues a session; the caller sets {@link Session#token()} as the cookie. */
    public Session createSession() {
        Instant now = clock.instant();
        sessions.values().removeIf(expiry -> !expiry.isAfter(now));
        while (sessions.size() >= MAX_SESSIONS) {
            // Bounded memory: drop the session closest to expiring rather than refuse to log in.
            sessions.entrySet().stream()
                    .min(Map.Entry.comparingByValue(Comparator.naturalOrder()))
                    .ifPresent(oldest -> sessions.remove(oldest.getKey()));
        }
        byte[] random = new byte[SESSION_TOKEN_BYTES];
        RANDOM.nextBytes(random);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        Instant expiresAt = now.plus(SESSION_TTL);
        sessions.put(tokenDigest(token), expiresAt);
        return new Session(token, expiresAt);
    }

    /** Ends the session named by the request's cookie, if any. */
    public void revokeSession(String cookieHeader) {
        sessionToken(cookieHeader).ifPresent(token -> sessions.remove(tokenDigest(token)));
    }

    public static String sessionCookie(String token) {
        return SESSION_COOKIE + "=" + token + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=" + SESSION_TTL.toSeconds();
    }

    public static String clearedSessionCookie() {
        return SESSION_COOKIE + "=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0";
    }

    public static void rejectUnauthorized(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer realm=\"nexora\"");
        Responses.send(exchange, STATUS_UNAUTHORIZED, Responses.JSON, UNAUTHORIZED_BODY);
    }

    private boolean hasLiveSession(String cookieHeader) {
        Optional<String> token = sessionToken(cookieHeader);
        if (token.isEmpty()) {
            return false;
        }
        String digest = tokenDigest(token.get());
        Instant expiry = sessions.get(digest);
        if (expiry == null) {
            return false;
        }
        if (!expiry.isAfter(clock.instant())) {
            sessions.remove(digest);
            return false;
        }
        return true;
    }

    /** RFC 6750: the scheme is case-insensitive and separated from the token by a space. */
    private static Optional<String> bearerToken(String authorization) {
        if (authorization == null) {
            return Optional.empty();
        }
        String value = authorization.strip();
        int space = value.indexOf(' ');
        if (space < 0 || !value.substring(0, space).equalsIgnoreCase(BEARER_SCHEME)) {
            return Optional.empty();
        }
        String token = value.substring(space + 1).strip();
        return token.isEmpty() ? Optional.empty() : Optional.of(token);
    }

    private static Optional<String> sessionToken(String cookieHeader) {
        if (cookieHeader == null) {
            return Optional.empty();
        }
        for (String pair : cookieHeader.split(";")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).strip().equals(SESSION_COOKIE)) {
                String value = pair.substring(eq + 1).strip();
                return SESSION_TOKEN.matcher(value).matches() ? Optional.of(value) : Optional.empty();
            }
        }
        return Optional.empty();
    }

    private static String tokenDigest(String token) {
        return HEX.formatHex(ApiKeyStore.digest(token));
    }

    /** The Origin addresses exactly the authority (host and port) the request was sent to. */
    private static boolean sameAuthority(String origin, String host) {
        Optional<URI> originUri = parseOrigin(origin);
        if (originUri.isEmpty() || host == null) {
            return false;
        }
        return authority(originUri.get()).equalsIgnoreCase(host.strip());
    }

    /** The socket's Origin must be the dashboard: same host the socket was opened on, dashboard port. */
    private static boolean originIsDashboard(String origin, String host, int dashboardPort) {
        Optional<URI> originUri = parseOrigin(origin);
        if (originUri.isEmpty() || host == null) {
            return false;
        }
        String socketHost;
        try {
            socketHost = URI.create("ws://" + host.strip()).getHost();
        } catch (IllegalArgumentException e) {
            return false;
        }
        return socketHost != null
                && socketHost.equalsIgnoreCase(originUri.get().getHost())
                && port(originUri.get()) == dashboardPort;
    }

    private static Optional<URI> parseOrigin(String origin) {
        if (origin == null || origin.isBlank() || "null".equals(origin)) {
            return Optional.empty();
        }
        try {
            URI uri = URI.create(origin.strip());
            return uri.getHost() == null ? Optional.empty() : Optional.of(uri);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** Host[:port] as a Host header would carry it: default ports are omitted. */
    private static String authority(URI uri) {
        return uri.getPort() < 0 ? uri.getHost() : uri.getHost() + ":" + uri.getPort();
    }

    private static int port(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }
}
