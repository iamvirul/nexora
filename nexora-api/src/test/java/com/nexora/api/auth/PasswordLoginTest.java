package com.nexora.api.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PasswordLoginTest {

    private static final String SERVER_KEY = "nxk_server-key-0123456789abcdefghijklm";
    private static final char[] PASSWORD = "correct horse battery".toCharArray();
    private static final String HOST = "127.0.0.1:9464";

    @TempDir
    Path dir;

    private UserStore users;
    private ApiAuth auth;

    @BeforeEach
    void setUp() {
        users = new UserStore(dir.resolve("users"), new PasswordHasher(1_000));
        users.add("alice", PASSWORD);
        auth = new ApiAuth(ApiKeyStore.digest(SERVER_KEY), users);
    }

    @Test
    void correctPasswordMintsAUserKeyThatWorksAsBearerUnderTheUsersName() {
        String key = login("alice").session().token();

        ApiAuth.Caller caller = auth.authenticate(bearer("POST", key));

        assertTrue(key.startsWith(ApiAuth.USER_KEY_PREFIX), key);
        assertEquals(ApiAuth.Credential.USER_KEY, caller.credential());
        assertEquals("alice", caller.name());
    }

    @Test
    void theSameKeyWorksAsTheDashboardCookie() {
        String key = login("Alice").session().token();

        ApiAuth.Caller caller =
                auth.authenticate(new ApiAuth.Request("GET", null, ApiAuth.SESSION_COOKIE + "=" + key, null, HOST));

        assertEquals(ApiAuth.Credential.SESSION, caller.credential());
        assertEquals("alice", caller.name());
    }

    @Test
    void userKeyOpensTheWebSocket() {
        String key = login("alice").session().token();

        assertTrue(auth.allowsWebSocket("Bearer " + key, null, null, "127.0.0.1:9465", 9464));
    }

    @Test
    void wrongPasswordAndUnknownUserAreRejectedTheSameWay() {
        assertInstanceOf(
                ApiAuth.LoginResult.Rejected.class,
                auth.passwordLogin("alice", "wrong password!!".toCharArray(), "c1"));
        assertInstanceOf(ApiAuth.LoginResult.Rejected.class, auth.passwordLogin("mallory", PASSWORD, "c1"));
    }

    @Test
    void repeatedFailuresThrottleRealAndUnknownNamesAlike() {
        for (int i = 0; i < LoginThrottle.MAX_FAILURES; i++) {
            auth.passwordLogin("alice", "wrong password!!".toCharArray(), "client-" + i);
            auth.passwordLogin("mallory", "wrong password!!".toCharArray(), "client-" + i);
        }

        assertInstanceOf(ApiAuth.LoginResult.Throttled.class, auth.passwordLogin("alice", PASSWORD, "fresh-client"));
        assertInstanceOf(ApiAuth.LoginResult.Throttled.class, auth.passwordLogin("mallory", PASSWORD, "fresh-client"));
    }

    @Test
    void parallelGuessesCannotOutrunTheLockout() throws Exception {
        // A slower hash widens the gap between checking the lock and recording the failure.
        UserStore slowUsers = new UserStore(dir.resolve("slow-users"), new PasswordHasher(50_000));
        slowUsers.add("carol", PASSWORD);
        ApiAuth slowAuth = new ApiAuth(ApiKeyStore.digest(SERVER_KEY), slowUsers);
        int guesses = 20;
        java.util.concurrent.CyclicBarrier start = new java.util.concurrent.CyclicBarrier(guesses);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(guesses);
        try {
            java.util.List<java.util.concurrent.Future<ApiAuth.LoginResult>> results = new java.util.ArrayList<>();
            for (int i = 0; i < guesses; i++) {
                String client = "client-" + i; // distinct clients, so only the per-username lock can stop them
                results.add(pool.submit(() -> {
                    start.await();
                    return slowAuth.passwordLogin("carol", "wrong password!!".toCharArray(), client);
                }));
            }
            long hashed = 0;
            for (java.util.concurrent.Future<ApiAuth.LoginResult> result : results) {
                if (result.get(30, java.util.concurrent.TimeUnit.SECONDS) instanceof ApiAuth.LoginResult.Rejected) {
                    hashed++;
                }
            }

            assertEquals(LoginThrottle.MAX_FAILURES, hashed, "only the first attempts may reach the password check");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void passwordChangeRevokesTheUsersKeys() {
        String key = login("alice").session().token();

        users.setPassword("alice", "another long password".toCharArray());

        assertFalse(auth.authenticate(bearer("GET", key)).authenticated());
    }

    @Test
    void removingTheUserRevokesTheirKeys() {
        String key = login("alice").session().token();

        users.remove("alice");

        assertFalse(auth.authenticate(bearer("GET", key)).authenticated());
    }

    @Test
    void logoutRevokesABearerUserKeyButNeverTheServerKey() {
        String key = login("alice").session().token();

        auth.revokeBearer("Bearer " + key);
        auth.revokeBearer("Bearer " + SERVER_KEY);

        assertFalse(auth.authenticate(bearer("GET", key)).authenticated());
        assertEquals(
                ApiAuth.Credential.API_KEY,
                auth.authenticate(bearer("GET", SERVER_KEY)).credential());
    }

    @Test
    void serverWithoutAUserStoreRejectsEveryPasswordLogin() {
        ApiAuth keyOnly = new ApiAuth(ApiKeyStore.digest(SERVER_KEY));

        assertFalse(keyOnly.passwordLoginEnabled());
        assertInstanceOf(ApiAuth.LoginResult.Rejected.class, keyOnly.passwordLogin("alice", PASSWORD, "c1"));
    }

    private ApiAuth.LoginResult.Accepted login(String username) {
        return assertInstanceOf(ApiAuth.LoginResult.Accepted.class, auth.passwordLogin(username, PASSWORD, "c1"));
    }

    private static ApiAuth.Request bearer(String method, String token) {
        return new ApiAuth.Request(method, "Bearer " + token, null, null, HOST);
    }
}
