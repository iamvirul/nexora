package com.nexora.api.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class ApiAuthTest {

    private static final String KEY = "nxk_test-key-0123456789abcdefghijklmnopq";

    private static final String HOST = "127.0.0.1:9464";
    private static final String DASHBOARD_ORIGIN = "http://127.0.0.1:9464";
    private static final int DASHBOARD_PORT = 9464;

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-13T10:00:00Z"));
    private final ApiAuth auth = new ApiAuth(ApiKeyStore.digest(KEY), clock);

    @Test
    void validBearerKeyAuthenticates() {
        assertEquals(ApiAuth.Credential.API_KEY, auth.authenticate(bearer("POST", "Bearer " + KEY)));
    }

    @Test
    void bearerSchemeIsCaseInsensitive() {
        assertEquals(ApiAuth.Credential.API_KEY, auth.authenticate(bearer("GET", "bEaReR " + KEY)));
    }

    @Test
    void wrongMissingOrMalformedCredentialsAreRejected() {
        assertEquals(ApiAuth.Credential.NONE, auth.authenticate(bearer("GET", "Bearer " + KEY + "x")));
        assertEquals(ApiAuth.Credential.NONE, auth.authenticate(bearer("GET", null)));
        assertEquals(ApiAuth.Credential.NONE, auth.authenticate(bearer("GET", "Bearer ")));
        assertEquals(ApiAuth.Credential.NONE, auth.authenticate(bearer("GET", "Basic " + KEY)));
        assertEquals(ApiAuth.Credential.NONE, auth.authenticate(bearer("GET", KEY)));
    }

    @Test
    void sessionCookieAuthenticatesReads() {
        String cookie = cookie(auth.createSession().token());

        assertEquals(ApiAuth.Credential.SESSION, auth.authenticate(request("GET", cookie, null)));
    }

    @Test
    void sessionCookieChangesStateOnlyFromThisServersOrigin() {
        String cookie = cookie(auth.createSession().token());

        assertEquals(ApiAuth.Credential.SESSION, auth.authenticate(request("POST", cookie, DASHBOARD_ORIGIN)));
        assertEquals(ApiAuth.Credential.NONE, auth.authenticate(request("POST", cookie, null)));
        // Same host, other port: SameSite would still send the cookie, so the Origin check is what stops it.
        assertEquals(ApiAuth.Credential.NONE, auth.authenticate(request("DELETE", cookie, "http://127.0.0.1:3000")));
        assertEquals(ApiAuth.Credential.NONE, auth.authenticate(request("POST", cookie, "https://evil.example")));
        assertEquals(ApiAuth.Credential.NONE, auth.authenticate(request("POST", cookie, "null")));
    }

    @Test
    void expiredSessionIsRejected() {
        String cookie = cookie(auth.createSession().token());

        clock.advance(ApiAuth.SESSION_TTL.plusSeconds(1));

        assertEquals(ApiAuth.Credential.NONE, auth.authenticate(request("GET", cookie, null)));
    }

    @Test
    void revokedSessionIsRejected() {
        String cookie = cookie(auth.createSession().token());

        auth.revokeSession(cookie);

        assertEquals(ApiAuth.Credential.NONE, auth.authenticate(request("GET", cookie, null)));
    }

    @Test
    void forgedSessionTokensAreRejected() {
        assertEquals(ApiAuth.Credential.NONE, auth.authenticate(request("GET", cookie("A".repeat(43)), null)));
        assertEquals(ApiAuth.Credential.NONE, auth.authenticate(request("GET", cookie("short"), null)));
        assertEquals(ApiAuth.Credential.NONE, auth.authenticate(request("GET", "other=1", null)));
    }

    @Test
    void sessionCountStaysBoundedAndNewestSessionStillWorks() {
        for (int i = 0; i < ApiAuth.MAX_SESSIONS + 5; i++) {
            clock.advance(Duration.ofMillis(1));
            auth.createSession();
        }
        clock.advance(Duration.ofMillis(1));
        String newest = cookie(auth.createSession().token());

        assertEquals(ApiAuth.Credential.SESSION, auth.authenticate(request("GET", newest, null)));
    }

    @Test
    void sessionCookieIsHttpOnlyAndStrict() {
        String header = ApiAuth.sessionCookie("token");

        assertTrue(header.contains("HttpOnly"), header);
        assertTrue(header.contains("SameSite=Strict"), header);
        assertTrue(header.contains("Path=/"), header);
        assertTrue(ApiAuth.clearedSessionCookie().contains("Max-Age=0"));
    }

    @Test
    void webSocketAcceptsBearerFromAnyClient() {
        assertTrue(auth.allowsWebSocket("Bearer " + KEY, null, null, "127.0.0.1:9465", DASHBOARD_PORT));
    }

    @Test
    void webSocketAcceptsSessionOnlyFromTheDashboard() {
        String cookie = cookie(auth.createSession().token());

        assertTrue(auth.allowsWebSocket(null, cookie, DASHBOARD_ORIGIN, "127.0.0.1:9465", DASHBOARD_PORT));
        assertFalse(auth.allowsWebSocket(null, cookie, "http://127.0.0.1:3000", "127.0.0.1:9465", DASHBOARD_PORT));
        assertFalse(auth.allowsWebSocket(null, cookie, "http://evil.example:9464", "127.0.0.1:9465", DASHBOARD_PORT));
        assertFalse(auth.allowsWebSocket(null, cookie, null, "127.0.0.1:9465", DASHBOARD_PORT));
        assertFalse(auth.allowsWebSocket(null, cookie, DASHBOARD_ORIGIN, "bad host", DASHBOARD_PORT));
    }

    @Test
    void webSocketWithoutCredentialsIsRejected() {
        assertFalse(auth.allowsWebSocket(null, null, DASHBOARD_ORIGIN, "127.0.0.1:9465", DASHBOARD_PORT));
    }

    private static ApiAuth.Request bearer(String method, String authorization) {
        return new ApiAuth.Request(method, authorization, null, null, HOST);
    }

    private static ApiAuth.Request request(String method, String cookie, String origin) {
        return new ApiAuth.Request(method, null, cookie, origin, HOST);
    }

    private static String cookie(String token) {
        return "theme=dark; " + ApiAuth.SESSION_COOKIE + "=" + token;
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
