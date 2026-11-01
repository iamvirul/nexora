package com.nexora.samples.login;

import com.nexora.api.NexoraEngine;
import com.nexora.api.auth.ApiAuth;
import com.nexora.api.auth.ApiKeyStore;
import com.nexora.api.auth.PasswordHasher;
import com.nexora.api.auth.UserStore;
import com.nexora.cli.DemoCapabilities;
import com.nexora.cli.ObserveServer;
import com.nexora.persistence.jdbc.JdbcExecutionStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Stream;

/**
 * Try username and password sign-in against the real observe server.
 *
 * <p>Starts {@link ObserveServer}, the same server {@code nexora observe} runs, in multi-tenant mode
 * with two demo users in different tenants and order-processing capabilities, then prints what to
 * try: the dashboard sign-in, tenant isolation, the CLI's {@code nexora login}, minting a user API
 * key with curl, the lockout, and live revocation.
 * Everything lives in a temp folder that is deleted on exit, and the server listens on localhost
 * only.
 */
public final class LoginDemoApp {

    static final String HOST = "127.0.0.1";
    static final int HTTP_PORT = 9470;
    static final int WEBSOCKET_PORT = HTTP_PORT + 1;
    private static final int EXIT_USAGE = 2;

    /**
     * Demo-only accounts, one per tenant, so each sees only its own executions. The passwords are
     * printed on purpose; never reuse them anywhere real.
     */
    static final List<DemoUser> USERS = List.of(
            new DemoUser("alice", "alice-demo-password", "acme"), new DemoUser("bob", "bob-demo-password", "globex"));

    record DemoUser(String username, String password, String tenant) {}

    /**
     * A running demo. {@code serverKey} is null when it came from {@value ApiKeyStore#ENV_VAR}, since
     * then the operator already knows it.
     */
    record Running(ObserveServer server, NexoraEngine engine, Path dir, String serverKey) implements AutoCloseable {

        Path usersFile() {
            return dir.resolve("users");
        }

        Path credentialsFile() {
            return dir.resolve("credentials");
        }

        @Override
        public void close() {
            server.close();
            engine.close();
            deleteRecursively(dir);
        }
    }

    private LoginDemoApp() {}

    public static void main(String[] args) throws Exception {
        Running demo;
        try {
            demo = start(HTTP_PORT, WEBSOCKET_PORT, System.getenv(ApiKeyStore.ENV_VAR));
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(EXIT_USAGE);
            return;
        }
        CountDownLatch shutdown = new CountDownLatch(1);
        Running running = demo;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                running.close();
            } finally {
                shutdown.countDown();
            }
        }));
        printGuide(demo);
        shutdown.await();
    }

    /**
     * Creates the demo users and starts the server. On failure, everything created so far is
     * removed again.
     *
     * @param envKey {@value ApiKeyStore#ENV_VAR}, or null to generate a key for this run
     */
    static Running start(int httpPort, int webSocketPort, String envKey) throws IOException {
        ApiKeyStore.Resolution key = ApiKeyStore.forThisRun(envKey);
        // createTempDirectory makes the folder owner-only on POSIX systems.
        Path dir = Files.createTempDirectory("nexora-login-demo-");
        NexoraEngine engine = null;
        try {
            UserStore users = new UserStore(dir.resolve("users"), new PasswordHasher());
            for (DemoUser user : USERS) {
                users.add(user.username(), user.password().toCharArray(), user.tenant());
            }
            // In-memory store so the dead letter, schedule and history panels work; gone on exit like the rest.
            NexoraEngine.Builder builder = NexoraEngine.builder().withExecutionStore(JdbcExecutionStore.h2InMemory());
            DemoCapabilities.wire(builder);
            engine = builder.build();
            ObserveServer server = ObserveServer.start(
                    engine,
                    new ApiAuth(key.digest(), users),
                    new ObserveServer.Config(
                                    HOST,
                                    httpPort,
                                    webSocketPort,
                                    ObserveServer.Config.DEFAULT_MAX_EXECUTIONS,
                                    ObserveServer.Config.DEFAULT_MAX_EVENTS)
                            .withMultiTenant(true));
            return new Running(server, engine, dir, key.newKey().orElse(null));
        } catch (IOException | RuntimeException e) {
            if (engine != null) {
                engine.close();
            }
            deleteRecursively(dir);
            throw e;
        }
    }

    private static void printGuide(Running demo) {
        String base = "http://localhost:" + demo.server().httpPort();
        String cli = "java -jar nexora-cli/target/nexora.jar";
        String creds = " --credentials-file " + demo.credentialsFile();
        DemoUser alice = USERS.get(0);
        DemoUser bob = USERS.get(1);

        System.out.println();
        System.out.println("Nexora Login Demo");
        System.out.println("=================");
        System.out.println("Dashboard:  " + base + "/");
        System.out.println();
        System.out.println("Demo users (in " + demo.dir() + ", deleted when you stop the demo):");
        for (DemoUser user : USERS) {
            System.out.printf("  %-8s %-22s tenant %s%n", user.username(), user.password(), user.tenant());
        }
        System.out.println("The server is multi-tenant: each user sees only their own tenant's executions.");
        System.out.println(
                demo.serverKey() != null
                        ? "Server key for this run: " + demo.serverKey()
                        : "Server key: from " + ApiKeyStore.ENV_VAR);
        System.out.println();
        System.out.println("Try it");
        System.out.println("------");
        System.out.println(
                "1. Dashboard: open " + base + "/ and sign in as " + alice.username() + " / " + alice.password());
        System.out.println(
                "   Run \"process order\" from the form. This console logs it as principal=" + alice.username() + ".");
        System.out.println();
        System.out.println("2. Tenants: sign out and sign in as " + bob.username() + " / " + bob.password() + ".");
        System.out.println("   " + alice.username() + "'s execution is not there: " + bob.username() + " is in tenant "
                + bob.tenant() + ", " + alice.username() + " in " + alice.tenant() + ".");
        System.out.println("   The server key reaches any tenant, but must name one:");
        System.out.println("   curl -H \"Authorization: Bearer <server key>\" -H \"X-Tenant-ID: " + alice.tenant()
                + "\" " + base + "/api/executions");
        System.out.println("   Without X-Tenant-ID the server answers 400; a user key naming another tenant gets 403.");
        System.out.println();
        System.out.println("3. Lockout: sign out, then enter a wrong password for " + bob.username() + " five times.");
        System.out.println("   The sixth attempt is refused for 15 minutes. The server key above still works.");
        System.out.println();
        System.out.println("4. CLI login (from the repository root, after `mvn -q install -DskipTests`):");
        System.out.println("   " + cli + " login --server " + base + " -u " + bob.username() + creds);
        System.out.println("   " + cli + " cancel <executionId> --server " + base + creds);
        System.out.println("   " + cli + " logout --server " + base + creds);
        System.out.println();
        System.out.println("5. A user API key with curl (it carries the user's tenant):");
        System.out.println("   curl -X POST " + base + "/api/keys -H 'Content-Type: application/json' \\");
        System.out.println(
                "        -d '{\"username\":\"" + alice.username() + "\",\"password\":\"" + alice.password() + "\"}'");
        System.out.println("   curl -H \"Authorization: Bearer <apiKey from above>\" " + base + "/api/process");
        System.out.println();
        System.out.println("6. Live revocation: while " + alice.username() + " is signed in, run");
        System.out.println("   " + cli + " user remove " + alice.username() + " --users-file " + demo.usersFile());
        System.out.println("   Her next request is refused and the dashboard asks her to sign in again.");
        System.out.println();
        System.out.println("Sign-ins, rejections and changes appear below as `audit` lines. Press Ctrl+C to stop.");
        System.out.println();
    }

    static void deleteRecursively(Path dir) {
        Objects.requireNonNull(dir, "dir");
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not delete the demo folder " + dir, e);
        }
    }
}
