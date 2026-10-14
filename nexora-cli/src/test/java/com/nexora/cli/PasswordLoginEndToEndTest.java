package com.nexora.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexora.api.NexoraEngine;
import com.nexora.api.auth.ApiAuth;
import com.nexora.api.auth.ApiKeyStore;
import com.nexora.api.auth.PasswordHasher;
import com.nexora.api.auth.SessionEndpoints;
import com.nexora.api.auth.UserStore;
import com.nexora.api.observability.NexoraObservability;
import com.nexora.persistence.jdbc.JdbcExecutionStore;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** Username and password login against a live observe server, over HTTP and through the CLI. */
class PasswordLoginEndToEndTest {

    private static final String LOOPBACK = "127.0.0.1";
    private static final String PASSWORD = "correct horse battery";
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path dir;

    private final HttpClient client = HttpClient.newHttpClient();
    private NexoraEngine engine;
    private NexoraObservability observability;
    private HttpServer server;
    private Path usersFile;
    private Path credentialsFile;

    @BeforeEach
    void setUp() throws Exception {
        usersFile = dir.resolve("users");
        credentialsFile = dir.resolve("credentials");
        // The real store and hasher a running server uses; `nexora user` edits the same file.
        UserStore users = new UserStore(usersFile, new PasswordHasher());
        users.add("alice", PASSWORD.toCharArray());
        engine = NexoraEngine.builder()
                .withExecutionStore(JdbcExecutionStore.h2InMemory())
                .build();
        observability = NexoraObservability.attach(engine);
        server = HttpServer.create(new InetSocketAddress(LOOPBACK, 0), 0);
        ObserveCommand.registerRoutes(
                server, engine, observability, new byte[0], new ApiAuth(ApiKeyStore.digest(TestAuth.KEY), users));
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        observability.close();
        engine.close();
    }

    @Test
    void correctPasswordIssuesAUserApiKeyThatWorksOnTheApi() throws Exception {
        HttpResponse<String> issued = postJson(SessionEndpoints.KEYS_PATH, credentials("alice", PASSWORD), null);
        JsonNode body = JSON.readTree(issued.body());
        String key = body.path("apiKey").asText();

        assertEquals(201, issued.statusCode());
        assertTrue(key.startsWith(ApiAuth.USER_KEY_PREFIX), key);
        assertTrue(issued.headers().firstValue("Set-Cookie").isEmpty(), "keys endpoint must not set a cookie");
        assertEquals(200, get("/api/process", "Bearer " + key).statusCode());
        assertEquals(
                "alice",
                JSON.readTree(get(SessionEndpoints.PATH, "Bearer " + key).body())
                        .path("user")
                        .asText());
    }

    @Test
    void dashboardSignInPutsTheUserKeyInAnHttpOnlyCookieOnly() throws Exception {
        HttpResponse<String> signedIn = postJson(SessionEndpoints.PATH, credentials("alice", PASSWORD), origin());
        String setCookie = signedIn.headers().firstValue("Set-Cookie").orElseThrow();
        String cookie = setCookie.substring(0, setCookie.indexOf(';'));

        assertEquals(200, signedIn.statusCode());
        assertTrue(setCookie.contains("HttpOnly"), setCookie);
        assertFalse(signedIn.body().contains(ApiAuth.USER_KEY_PREFIX), "the key must not reach page script");
        HttpResponse<String> who = client.send(
                request(SessionEndpoints.PATH).header("Cookie", cookie).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals("alice", JSON.readTree(who.body()).path("user").asText());
    }

    @Test
    void wrongPasswordsAreRejectedThenThrottled() throws Exception {
        for (int i = 0; i < 5; i++) {
            assertEquals(
                    401,
                    postJson(SessionEndpoints.KEYS_PATH, credentials("alice", "wrong password " + i), null)
                            .statusCode());
        }

        HttpResponse<String> locked = postJson(SessionEndpoints.KEYS_PATH, credentials("alice", PASSWORD), null);

        assertEquals(429, locked.statusCode());
        assertTrue(locked.headers().firstValue("Retry-After").isPresent());
    }

    @Test
    void loginMustBeJsonAndSameOrigin() throws Exception {
        HttpResponse<String> form = client.send(
                request(SessionEndpoints.KEYS_PATH)
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString("username=alice&password=x"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> crossSite =
                postJson(SessionEndpoints.PATH, credentials("alice", PASSWORD), "https://evil.example");

        assertEquals(415, form.statusCode());
        assertEquals(401, crossSite.statusCode());
    }

    @Test
    void aUserKeyCannotMintADashboardSession() throws Exception {
        String key = JSON.readTree(postJson(SessionEndpoints.KEYS_PATH, credentials("alice", PASSWORD), null)
                        .body())
                .path("apiKey")
                .asText();

        HttpResponse<String> renewed = client.send(
                request(SessionEndpoints.PATH)
                        .header("Authorization", "Bearer " + key)
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(401, renewed.statusCode());
    }

    @Test
    void cliUserLoginCancelLogoutRoundTrip() throws Exception {
        Result added = run(
                new UserCommand(),
                "newpassword-for-bob",
                "add",
                "bob",
                "--password-stdin",
                "--users-file",
                usersFile.toString());
        assertEquals(0, added.exitCode(), added.err());

        Result login = run(
                new LoginCommand(),
                "newpassword-for-bob",
                "--server",
                origin(),
                "--username",
                "bob",
                "--password-stdin",
                "--credentials-file",
                credentialsFile.toString());
        assertEquals(0, login.exitCode(), login.err());
        assertTrue(Files.readString(credentialsFile).contains(ApiAuth.USER_KEY_PREFIX));

        // Authenticated by the saved user key: the server answers 404 for the unknown id, not 401.
        Result cancel = run(
                new CancelCommand(),
                "",
                "no-such-execution",
                "--server",
                origin(),
                "--credentials-file",
                credentialsFile.toString());
        assertTrue(cancel.err().contains("no execution found"), cancel.err());

        Result logout =
                run(new LogoutCommand(), "", "--server", origin(), "--credentials-file", credentialsFile.toString());
        assertEquals(0, logout.exitCode(), logout.err());
        assertTrue(logout.out().contains("Logged out"), logout.out());
        assertFalse(Files.readString(credentialsFile).contains(ApiAuth.USER_KEY_PREFIX));
    }

    @Test
    void cliLoginReportsAWrongPassword() {
        Result login = run(
                new LoginCommand(),
                "not the password",
                "--server",
                origin(),
                "--username",
                "alice",
                "--password-stdin",
                "--credentials-file",
                credentialsFile.toString());

        assertEquals(1, login.exitCode());
        assertTrue(login.err().contains("wrong username or password"), login.err());
        assertFalse(Files.exists(credentialsFile));
    }

    @Test
    void removingAUserEndsTheirKeyOnTheRunningServer() throws Exception {
        String key = JSON.readTree(postJson(SessionEndpoints.KEYS_PATH, credentials("alice", PASSWORD), null)
                        .body())
                .path("apiKey")
                .asText();

        Result removed = run(new UserCommand(), "", "remove", "alice", "--users-file", usersFile.toString());

        assertEquals(0, removed.exitCode(), removed.err());
        assertEquals(401, get("/api/process", "Bearer " + key).statusCode());
    }

    private Result run(Object command, String stdin, String... args) {
        if (command instanceof UserCommand user) user.in = stdinOf(stdin);
        if (command instanceof LoginCommand login) login.in = stdinOf(stdin);
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine cmd = new CommandLine(command);
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));
        int exitCode = cmd.execute(args);
        return new Result(exitCode, out.toString(), err.toString());
    }

    private static ByteArrayInputStream stdinOf(String line) {
        return new ByteArrayInputStream((line + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private record Result(int exitCode, String out, String err) {}

    private static String credentials(String username, String password) throws Exception {
        return JSON.writeValueAsString(java.util.Map.of("username", username, "password", password));
    }

    private HttpResponse<String> postJson(String path, String body, String origin) throws Exception {
        HttpRequest.Builder builder = request(path)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (origin != null) builder.header("Origin", origin);
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String authorization) throws Exception {
        return client.send(
                request(path).header("Authorization", authorization).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(origin() + path));
    }

    private String origin() {
        return "http://" + LOOPBACK + ":" + server.getAddress().getPort();
    }
}
