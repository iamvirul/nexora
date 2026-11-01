package com.nexora.api.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nexora.core.tenant.TenantId;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Users and credentials bound to tenants, and the rules that turn a request into a tenant scope. */
class TenantAuthTest {

    private static final String SERVER_KEY = "nxk_server-key-0123456789abcdefghijklm";
    private static final char[] PASSWORD = "correct horse battery".toCharArray();
    private static final String HOST = "127.0.0.1:9464";
    private static final int DASHBOARD_PORT = 9464;

    @TempDir
    Path dir;

    private UserStore users;
    private ApiAuth auth;
    private final TenantResolver multi = TenantResolver.multiTenant();

    @BeforeEach
    void setUp() {
        users = new UserStore(dir.resolve("users"), new PasswordHasher(1_000));
        users.add("alice", PASSWORD, "acme");
        users.add("dave", PASSWORD);
        auth = new ApiAuth(ApiKeyStore.digest(SERVER_KEY), users);
    }

    @Test
    void usersKeepTheirTenantAcrossReloadsAndPasswordChanges() {
        users.setPassword("alice", "another long password".toCharArray());

        UserStore reloaded = new UserStore(users.file(), new PasswordHasher(1_000));

        assertEquals("acme", reloaded.tenantOf("alice").orElseThrow());
        assertEquals(TenantId.DEFAULT, reloaded.tenantOf("dave").orElseThrow());
    }

    @Test
    void defaultTenantUsersAreWrittenInThePreTenantFormat() throws Exception {
        List<String> lines = Files.readAllLines(users.file());

        String dave =
                lines.stream().filter(l -> l.startsWith("dave:")).findFirst().orElseThrow();
        String alice =
                lines.stream().filter(l -> l.startsWith("alice:")).findFirst().orElseThrow();

        assertEquals(2, dave.split(":").length);
        assertTrue(alice.endsWith(":acme"), alice);
    }

    @Test
    void filesWrittenBeforeTenantsLoadIntoTheDefaultTenant() throws Exception {
        Path legacy = dir.resolve("legacy-users");
        String daveLine = Files.readAllLines(users.file()).stream()
                .filter(l -> l.startsWith("dave:"))
                .findFirst()
                .orElseThrow();
        Files.writeString(legacy, "# old file\n" + daveLine + "\n");

        UserStore store = new UserStore(legacy, new PasswordHasher(1_000));

        assertEquals(TenantId.DEFAULT, store.tenantOf("dave").orElseThrow());
    }

    @Test
    void malformedTenantInTheFileIsRejected() throws Exception {
        String aliceLine = Files.readAllLines(users.file()).stream()
                .filter(l -> l.startsWith("alice:"))
                .findFirst()
                .orElseThrow();
        Path broken = dir.resolve("broken-users");
        Files.writeString(broken, aliceLine.replace(":acme", ":Not Valid") + "\n");

        assertThrows(IllegalStateException.class, () -> new UserStore(broken, new PasswordHasher(1_000)));
    }

    @Test
    void invalidTenantsAreRejectedOnWrite() {
        assertThrows(IllegalArgumentException.class, () -> users.add("carol", PASSWORD, "Acme"));
        assertThrows(IllegalArgumentException.class, () -> users.setTenant("alice", "a:b"));
        assertThrows(IllegalArgumentException.class, () -> users.setTenant("nobody", "acme"));
    }

    @Test
    void userKeyAndSessionCarryTheUsersTenant() {
        String key = login("alice").session().token();

        ApiAuth.Caller bearer = auth.authenticate(new ApiAuth.Request("GET", "Bearer " + key, null, null, HOST));
        ApiAuth.Caller cookie =
                auth.authenticate(new ApiAuth.Request("GET", null, ApiAuth.SESSION_COOKIE + "=" + key, null, HOST));

        assertEquals("acme", bearer.tenantId());
        assertEquals("acme", cookie.tenantId());
        assertEquals(
                "acme",
                auth.authenticateWebSocket("Bearer " + key, null, null, HOST, DASHBOARD_PORT)
                        .orElseThrow()
                        .tenantId());
    }

    @Test
    void movingAUserToAnotherTenantEndsTheirSessions() {
        String key = login("alice").session().token();

        users.setTenant("alice", "globex");

        assertEquals(
                ApiAuth.Credential.NONE,
                auth.authenticate(new ApiAuth.Request("GET", "Bearer " + key, null, null, HOST))
                        .credential());
        String newKey = login("alice").session().token();
        assertEquals(
                "globex",
                auth.authenticate(new ApiAuth.Request("GET", "Bearer " + newKey, null, null, HOST))
                        .tenantId());
    }

    @Test
    void serverKeyIsBoundToNoTenantAndKeyHolderSessionsToTheOneNamed() {
        ApiAuth.Caller key = auth.authenticate(new ApiAuth.Request("GET", "Bearer " + SERVER_KEY, null, null, HOST));
        String pinned = auth.createSession("globex").token();
        String unpinned = auth.createSession().token();

        assertNull(key.tenantId());
        assertEquals(
                "globex",
                auth.currentSession(ApiAuth.SESSION_COOKIE + "=" + pinned)
                        .orElseThrow()
                        .tenantId());
        assertNull(auth.currentSession(ApiAuth.SESSION_COOKIE + "=" + unpinned)
                .orElseThrow()
                .tenantId());
        assertThrows(IllegalArgumentException.class, () -> auth.createSession("Bad Tenant"));
    }

    @Test
    void singleTenantModeIgnoresTheHeaderEntirely() {
        TenantResolver single = TenantResolver.singleTenant();

        assertEquals(TenantScope.UNSCOPED, scoped(single.resolve(userOf("acme"), "globex")));
        assertEquals(TenantScope.UNSCOPED, scoped(single.resolve(serverKey(), null)));
        assertEquals(TenantScope.UNSCOPED, scoped(single.resolve(serverKey(), "Not Valid")));
    }

    @Test
    void multiTenantUserIsScopedToTheirTenantWithOrWithoutTheHeader() {
        assertEquals(TenantScope.of("acme"), scoped(multi.resolve(userOf("acme"), null)));
        assertEquals(TenantScope.of("acme"), scoped(multi.resolve(userOf("acme"), "acme")));
    }

    @Test
    void multiTenantUserNamingAnotherTenantIsForbidden() {
        TenantResolver.Resolution.Rejected rejected =
                assertInstanceOf(TenantResolver.Resolution.Rejected.class, multi.resolve(userOf("acme"), "globex"));

        assertEquals(403, rejected.status());
        assertEquals("TENANT_FORBIDDEN", rejected.code());
    }

    @Test
    void multiTenantServerKeyMustNameAValidTenant() {
        TenantResolver.Resolution.Rejected missing =
                assertInstanceOf(TenantResolver.Resolution.Rejected.class, multi.resolve(serverKey(), null));
        TenantResolver.Resolution.Rejected blank =
                assertInstanceOf(TenantResolver.Resolution.Rejected.class, multi.resolve(serverKey(), "  "));
        TenantResolver.Resolution.Rejected invalid =
                assertInstanceOf(TenantResolver.Resolution.Rejected.class, multi.resolve(serverKey(), "Acme Corp"));

        assertEquals(400, missing.status());
        assertEquals("TENANT_REQUIRED", missing.code());
        assertEquals("TENANT_REQUIRED", blank.code());
        assertEquals(400, invalid.status());
        assertEquals("INVALID_TENANT_ID", invalid.code());
        assertEquals(TenantScope.of("globex"), scoped(multi.resolve(serverKey(), "globex")));
    }

    @Test
    void scopeAllowsOnlyItsOwnTenantUnlessUnscoped() {
        assertTrue(TenantScope.of("acme").allows("acme"));
        assertEquals(false, TenantScope.of("acme").allows("globex"));
        assertTrue(TenantScope.UNSCOPED.allows("anything"));
    }

    private ApiAuth.LoginResult.Accepted login(String username) {
        return assertInstanceOf(ApiAuth.LoginResult.Accepted.class, auth.passwordLogin(username, PASSWORD, "c1"));
    }

    private static ApiAuth.Caller userOf(String tenantId) {
        return new ApiAuth.Caller(ApiAuth.Credential.USER_KEY, "someone", tenantId);
    }

    private static ApiAuth.Caller serverKey() {
        return new ApiAuth.Caller(ApiAuth.Credential.API_KEY, ApiAuth.API_KEY_PRINCIPAL);
    }

    private static TenantScope scoped(TenantResolver.Resolution resolution) {
        return assertInstanceOf(TenantResolver.Resolution.Scoped.class, resolution)
                .scope();
    }
}
