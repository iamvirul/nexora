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
    private static final String COOKIE_ATTRIBUTES = "; Path=/; HttpOnly; SameSite=Strict";
    public static final Duration SESSION_TTL = Duration.ofHours(12);
    public static final int MAX_SESSIONS = 1_000;

    private static final Logger log = LoggerFactory.getLogger(ApiAuth.class);
    private static final String BEARER_SCHEME = "bearer";
    private static final int SESSION_TOKEN_BYTES = 32;
    // The prefix plus base64url of 32 bytes without padding; anything else cannot be a key this server issued.
    private static final Pattern SESSION_TOKEN = Pattern.compile("nxu_[A-Za-z0-9_-]{43}");
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    private static final int STATUS_UNAUTHORIZED = 401;
    private static final String UNAUTHORIZED_BODY = "{\"error\":{\"code\":\"UNAUTHORIZED\","
            + "\"message\":\"A valid API key is required: send Authorization: Bearer <key>\"}}";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();

    /** How a request proved who it is. */
    public enum Credential {
        /** The server's own API key. */
        API_KEY,
        /** A user API key from login, sent as a Bearer header. */
        USER_KEY,
        /** A user API key from login, sent as the HttpOnly session cookie by the dashboard. */
        SESSION,
        NONE
    }

    /**
     * Prefix of user API keys minted at login, so they are recognisable in config and by secret
     * scanners, and never confused with the server's own {@code nxk_} key.
     */
    public static final String USER_KEY_PREFIX = "nxu_";

    /** Who made a request: how they authenticated, and the name the audit log records. */
    public record Caller(Credential credential, String name) {
        static final Caller ANONYMOUS = new Caller(Credential.NONE, null);

        public boolean authenticated() {
            return credential != Credential.NONE;
        }
    }

    /** The audit name for anything authenticated by the API key rather than a named user. */
    public static final String API_KEY_PRINCIPAL = "api-key";

    /** Outcome of a username and password login. */
    public sealed interface LoginResult {
        record Accepted(Session session, String user) implements LoginResult {}

        /** Wrong username or password; deliberately does not say which. */
        record Rejected() implements LoginResult {}

        /** Too many attempts for this username or from this client; try again after {@code retryAfter}. */
        record Throttled(Duration retryAfter) implements LoginResult {}
    }

    /** A live session as seen by {@code GET /api/session}; {@code user} is null for API key sessions. */
    public record SessionInfo(String user, Instant expiresAt) {}

    private record SessionEntry(Instant expiresAt, String user, String passwordFingerprint) {}

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
    private final Optional<UserStore> users;
    private final Clock clock;
    private final LoginThrottle throttle;
    /** SHA-256 hex of each live session token, mapped to its entry. Tokens themselves are never kept. */
    private final Map<String, SessionEntry> sessions = new ConcurrentHashMap<>();

    /**
     * @param users dashboard accounts for username and password login, or null to allow only the API key
     */
    public ApiAuth(byte[] keyDigest, UserStore users, Clock clock) {
        this.keyDigest = Objects.requireNonNull(keyDigest, "keyDigest").clone();
        this.users = Optional.ofNullable(users);
        this.clock = Objects.requireNonNull(clock, "clock");
        this.throttle = new LoginThrottle(clock);
    }

    public ApiAuth(byte[] keyDigest, UserStore users) {
        this(keyDigest, users, Clock.systemUTC());
    }

    public ApiAuth(byte[] keyDigest, Clock clock) {
        this(keyDigest, null, clock);
    }

    public ApiAuth(byte[] keyDigest) {
        this(keyDigest, null, Clock.systemUTC());
    }

    /** Whether this server has a user store, and so offers username and password login. */
    public boolean passwordLoginEnabled() {
        return users.isPresent();
    }

    /** Wraps {@code handler} so it only runs for an authenticated request; others get 401 first. */
    public HttpHandler protect(HttpHandler handler) {
        Objects.requireNonNull(handler, "handler");
        return exchange -> {
            Caller caller = authenticate(Request.of(exchange));
            if (!caller.authenticated()) {
                log.warn(
                        "Rejected unauthenticated API request method={} path={} remote={}",
                        exchange.getRequestMethod(),
                        exchange.getRequestURI().getPath(),
                        exchange.getRemoteAddress());
                rejectUnauthorized(exchange);
                return;
            }
            if (!SAFE_METHODS.contains(exchange.getRequestMethod().toUpperCase(Locale.ROOT))) {
                // Audit trail for state changes: who (user, or the shared key), what, and from where.
                log.info(
                        "Authorized API request method={} path={} principal={} credential={} remote={}",
                        exchange.getRequestMethod(),
                        exchange.getRequestURI().getPath(),
                        caller.name(),
                        caller.credential(),
                        exchange.getRemoteAddress());
            }
            handler.handle(exchange);
        };
    }

    /** Bearer key first; a session cookie only counts when the Origin rule above is met. */
    public Caller authenticate(Request request) {
        Optional<String> bearer = bearerToken(request.authorization());
        if (bearer.filter(this::isApiKey).isPresent()) {
            return new Caller(Credential.API_KEY, API_KEY_PRINCIPAL);
        }
        // A bearer header is never sent by a browser on its own, so no Origin rule applies to it.
        Optional<SessionEntry> userKey =
                bearer.filter(token -> SESSION_TOKEN.matcher(token).matches()).flatMap(this::liveToken);
        if (userKey.isPresent()) {
            return new Caller(Credential.USER_KEY, principal(userKey.get()));
        }
        boolean needsOrigin = !SAFE_METHODS.contains(request.method().toUpperCase(Locale.ROOT));
        if (needsOrigin && !sameAuthority(request.origin(), request.host())) {
            return Caller.ANONYMOUS;
        }
        return liveSession(request.cookie())
                .map(entry -> new Caller(Credential.SESSION, principal(entry)))
                .orElse(Caller.ANONYMOUS);
    }

    /**
     * Checks a username and password and opens a session for that user. Throttled before any
     * hashing; an unknown username costs the same time and counts the same as a wrong password.
     *
     * @param client the caller's address, for the per-client attempt limit
     */
    public LoginResult passwordLogin(String username, char[] password, String client) {
        String name = UserStore.normalize(username);
        // Reserved before hashing and counted as a failure until proven right, so parallel guesses cannot outrun the
        // lock.
        Optional<Duration> wait = throttle.reserve(name, client);
        if (wait.isPresent()) {
            log.warn("Throttled dashboard login user={} client={}", loggable(name), client);
            return new LoginResult.Throttled(wait.get());
        }
        if (users.isEmpty()) {
            // No accounts at all on this server, so nothing to time against: reject outright.
            return new LoginResult.Rejected();
        }
        UserStore store = users.get();
        Optional<PasswordHasher.Hash> stored = store.find(name);
        boolean matches;
        if (stored.isPresent()) {
            matches = store.hasher().matches(password, stored.get());
        } else {
            store.hasher().burnEquivalentTime(password);
            matches = false;
        }
        if (!matches) {
            log.warn("Rejected dashboard login user={} client={}", loggable(name), client);
            return new LoginResult.Rejected();
        }
        throttle.recordSuccess(name);
        Session session = issue(name, stored.get().fingerprint());
        log.info("Dashboard login user={} client={} expiresAt={}", name, client, session.expiresAt());
        return new LoginResult.Accepted(session, name);
    }

    /** Whether the request's Origin names exactly the host and port it was sent to. */
    public static boolean isSameOrigin(Request request) {
        return sameAuthority(request.origin(), request.host());
    }

    /** The session named by the cookie, if it is still live. */
    public Optional<SessionInfo> currentSession(String cookieHeader) {
        return liveSession(cookieHeader).map(entry -> new SessionInfo(entry.user(), entry.expiresAt()));
    }

    /**
     * Admits a WebSocket handshake on {@code bearer} or on a session cookie sent from the dashboard,
     * which lives on {@code dashboardPort} of the host the socket was opened on.
     */
    public boolean allowsWebSocket(String authorization, String cookie, String origin, String host, int dashboardPort) {
        Optional<String> bearer = bearerToken(authorization);
        if (bearer.filter(this::isApiKey).isPresent()
                || bearer.filter(token -> SESSION_TOKEN.matcher(token).matches())
                        .flatMap(this::liveToken)
                        .isPresent()) {
            return true;
        }
        return liveSession(cookie).isPresent() && originIsDashboard(origin, host, dashboardPort);
    }

    /** Compares digests, not keys: equal-length inputs to {@link MessageDigest#isEqual}, so timing reveals nothing. */
    public boolean isApiKey(String presented) {
        return MessageDigest.isEqual(ApiKeyStore.digest(presented), keyDigest);
    }

    /** A newly issued session: the token to set as the cookie, and when it stops being accepted. */
    public record Session(String token, Instant expiresAt) {}

    /** Issues a session for the API key holder; the caller sets {@link Session#token()} as the cookie. */
    public Session createSession() {
        return issue(null, null);
    }

    private Session issue(String user, String passwordFingerprint) {
        Instant now = clock.instant();
        sessions.values().removeIf(entry -> !entry.expiresAt().isAfter(now));
        while (sessions.size() >= MAX_SESSIONS) {
            // Bounded memory: drop the session closest to expiring rather than refuse to log in.
            sessions.entrySet().stream()
                    .min(Map.Entry.comparingByValue(Comparator.comparing(SessionEntry::expiresAt)))
                    .ifPresent(oldest -> sessions.remove(oldest.getKey()));
        }
        byte[] random = new byte[SESSION_TOKEN_BYTES];
        RANDOM.nextBytes(random);
        String token = USER_KEY_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        Instant expiresAt = now.plus(SESSION_TTL);
        sessions.put(tokenDigest(token), new SessionEntry(expiresAt, user, passwordFingerprint));
        return new Session(token, expiresAt);
    }

    /** Ends the session named by the request's cookie, if any. */
    public void revokeSession(String cookieHeader) {
        sessionToken(cookieHeader).ifPresent(token -> sessions.remove(tokenDigest(token)));
    }

    /** Ends the user API key sent as {@code Authorization: Bearer}, if it is one; the server key cannot be revoked. */
    public void revokeBearer(String authorization) {
        bearerToken(authorization)
                .filter(token -> SESSION_TOKEN.matcher(token).matches())
                .ifPresent(token -> sessions.remove(tokenDigest(token)));
    }

    /** The user API key sent as a Bearer header, if it is a live one this server issued. */
    public Optional<SessionInfo> currentUserKey(String authorization) {
        return bearerToken(authorization)
                .filter(token -> SESSION_TOKEN.matcher(token).matches())
                .flatMap(this::liveToken)
                .map(entry -> new SessionInfo(entry.user(), entry.expiresAt()));
    }

    /** @param secure adds {@code Secure}, so the browser never sends the cookie over plain HTTP */
    public static String sessionCookie(String token, boolean secure) {
        return SESSION_COOKIE + "=" + token + COOKIE_ATTRIBUTES + secureAttribute(secure) + "; Max-Age="
                + SESSION_TTL.toSeconds();
    }

    public static String clearedSessionCookie(boolean secure) {
        return SESSION_COOKIE + "=" + COOKIE_ATTRIBUTES + secureAttribute(secure) + "; Max-Age=0";
    }

    private static String secureAttribute(boolean secure) {
        return secure ? "; Secure" : "";
    }

    public static void rejectUnauthorized(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer realm=\"nexora\"");
        Responses.send(exchange, STATUS_UNAUTHORIZED, Responses.JSON, UNAUTHORIZED_BODY);
    }

    /**
     * A session is live until it expires, and a user's session also ends the moment that user is
     * removed or their password changes (every new password gets a new salt, so a new fingerprint).
     */
    private Optional<SessionEntry> liveSession(String cookieHeader) {
        return sessionToken(cookieHeader).flatMap(this::liveToken);
    }

    private static String principal(SessionEntry entry) {
        return entry.user() != null ? entry.user() : API_KEY_PRINCIPAL;
    }

    /** Same rules for a key whether it arrives as a cookie or a Bearer header. */
    private Optional<SessionEntry> liveToken(String token) {
        String digest = tokenDigest(token);
        SessionEntry entry = sessions.get(digest);
        if (entry == null) {
            return Optional.empty();
        }
        boolean expired = !entry.expiresAt().isAfter(clock.instant());
        boolean userRevoked = entry.user() != null
                && !users.flatMap(store -> store.find(entry.user()))
                        .map(hash -> hash.fingerprint().equals(entry.passwordFingerprint()))
                        .orElse(false);
        if (expired || userRevoked) {
            sessions.remove(digest);
            return Optional.empty();
        }
        return Optional.of(entry);
    }

    /** Attacker-supplied usernames reach the log only when they pass validation, so no line breaks or controls. */
    private static String loggable(String username) {
        try {
            return UserStore.requireValidUsername(username);
        } catch (IllegalArgumentException e) {
            return "<invalid>";
        }
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
