package com.nexora.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexora.api.NexoraEngine;
import com.nexora.api.auth.ApiAuth;
import com.nexora.api.auth.ApiKeyStore;
import com.nexora.api.auth.PasswordHasher;
import com.nexora.api.auth.SessionEndpoints;
import com.nexora.api.auth.UserStore;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

/** The observe server over HTTPS and WSS, reached the way browsers, curl and the CLI reach it. */
class ObserveServerTlsTest {

    private static final String LOOPBACK = "127.0.0.1";
    private static final String PASSWORD = "correct horse battery";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path dir;

    private final ListAppender<ILoggingEvent> serverLog = new ListAppender<>();
    private final Logger serverLogger = (Logger) LoggerFactory.getLogger(ObserveServer.class);
    private NexoraEngine engine;
    private ApiAuth auth;
    private Path caFile;
    private ObserveTls tls;
    private ObserveServer server;

    @BeforeEach
    void setUp() throws Exception {
        serverLog.start();
        serverLogger.addAppender(serverLog);
        DevCertificates.Issued issued = DevCertificates.issue(DevCertificates.DEFAULT_HOSTS, 30, Instant.now());
        caFile = Files.writeString(dir.resolve("ca.pem"), PemFiles.certificatePem(issued.caCertificate()));
        Path certificate =
                Files.writeString(dir.resolve("server.pem"), PemFiles.certificatePem(issued.serverCertificate()));
        Path key = Files.writeString(dir.resolve("server-key.pem"), PemFiles.privateKeyPem(issued.serverKey()));
        tls = ObserveTls.fromPem(certificate, key);

        UserStore users = new UserStore(dir.resolve("users"), new PasswordHasher());
        users.add("alice", PASSWORD.toCharArray());
        auth = new ApiAuth(ApiKeyStore.digest(TestAuth.KEY), users);
        engine = NexoraEngine.builder().build();
    }

    @AfterEach
    void tearDown() {
        serverLogger.detachAppender(serverLog);
        if (server != null) {
            server.close();
        }
        engine.close();
    }

    @Test
    void apiIsServedOverHttpsToAClientThatTrustsTheCa() throws Exception {
        server = startTls();

        HttpResponse<String> live =
                trustingClient().send(get("/health/live").build(), HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> process = trustingClient()
                .send(
                        get("/api/process")
                                .header("Authorization", "Bearer " + TestAuth.KEY)
                                .build(),
                        HttpResponse.BodyHandlers.ofString());

        assertTrue(server.tlsEnabled());
        assertEquals(200, live.statusCode());
        assertEquals(200, process.statusCode());
        String protocol = live.sslSession().orElseThrow().getProtocol();
        assertTrue(List.of(ObserveTls.PROTOCOLS).contains(protocol), protocol);
    }

    @Test
    void clientThatDoesNotTrustTheCaIsRefused() throws Exception {
        server = startTls();
        HttpClient jvmDefaults = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

        IOException error = assertThrows(
                IOException.class,
                () -> jvmDefaults.send(get("/health/live").build(), HttpResponse.BodyHandlers.ofString()));

        assertInstanceOf(SSLHandshakeException.class, error);
    }

    @Test
    void dashboardSessionCookieIsSecureOverHttps() throws Exception {
        server = startTls();
        String body = JSON.writeValueAsString(Map.of("username", "alice", "password", PASSWORD));

        HttpResponse<String> signedIn = trustingClient()
                .send(
                        request(SessionEndpoints.PATH)
                                .header("Content-Type", "application/json")
                                .header("Origin", origin())
                                .POST(HttpRequest.BodyPublishers.ofString(body))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());

        assertEquals(200, signedIn.statusCode(), signedIn.body());
        String cookie = signedIn.headers().firstValue("Set-Cookie").orElseThrow();
        assertTrue(cookie.contains("; Secure"), cookie);
        assertTrue(cookie.contains("HttpOnly"), cookie);
    }

    @Test
    void snapshotStreamIsServedOverWss() throws Exception {
        server = startTls();

        WebSocket socket = trustingClient()
                .newWebSocketBuilder()
                .header("Authorization", "Bearer " + TestAuth.KEY)
                .connectTimeout(TIMEOUT)
                .buildAsync(URI.create("wss://localhost:" + server.webSocketPort() + "/"), new WebSocket.Listener() {})
                .get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);

        assertFalse(socket.isInputClosed());
        socket.abort();
    }

    @Test
    void plainHttpStartIsLoggedAtWarnAndTlsStartIsNot() throws Exception {
        int[] ports = freePorts();
        try (ObserveServer plain = ObserveServer.start(
                engine,
                auth,
                new ObserveServer.Config(
                        LOOPBACK,
                        ports[0],
                        ports[1],
                        ObserveServer.Config.DEFAULT_MAX_EXECUTIONS,
                        ObserveServer.Config.DEFAULT_MAX_EVENTS))) {
            assertFalse(plain.tlsEnabled());
        }
        assertEquals(1, plainHttpWarnings(), "plain start");

        server = startTls();
        assertEquals(1, plainHttpWarnings(), "a TLS start adds no plain-HTTP warning");
    }

    @Test
    void cliLoginNeedsTheCaAndNamesTheFlagWhenItIsMissing() throws Exception {
        server = startTls();
        Path credentials = dir.resolve("credentials");

        Result untrusted = login("--server", origin(), "--credentials-file", credentials.toString());
        Result trusted = login(
                "--server", origin(), "--cacert", caFile.toString(), "--credentials-file", credentials.toString());

        assertEquals(1, untrusted.exitCode());
        assertTrue(untrusted.err().contains("pass --cacert"), untrusted.err());
        assertEquals(0, trusted.exitCode(), trusted.err());
        assertTrue(Files.readString(credentials).contains(ApiAuth.USER_KEY_PREFIX));
    }

    @Test
    void unreadableCacertIsAUsageError() {
        Result result = login(
                "--server",
                "https://localhost:1",
                "--cacert",
                dir.resolve("missing.pem").toString());

        assertEquals(2, result.exitCode());
        assertTrue(result.err().contains("Invalid --cacert"), result.err());
    }

    private ObserveServer startTls() throws Exception {
        int[] ports = freePorts();
        ObserveServer.Config config = new ObserveServer.Config(
                        LOOPBACK,
                        ports[0],
                        ports[1],
                        ObserveServer.Config.DEFAULT_MAX_EXECUTIONS,
                        ObserveServer.Config.DEFAULT_MAX_EVENTS)
                .withTls(tls);
        return ObserveServer.start(engine, auth, config);
    }

    private long plainHttpWarnings() {
        return serverLog.list.stream()
                .filter(e ->
                        e.getLevel() == Level.WARN && e.getFormattedMessage().startsWith("Serving plain HTTP"))
                .count();
    }

    private HttpClient trustingClient() {
        SSLContext trust = ObserveTls.trusting(caFile);
        return HttpClient.newBuilder().sslContext(trust).connectTimeout(TIMEOUT).build();
    }

    private HttpRequest.Builder get(String path) {
        return request(path).GET();
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(origin() + path)).timeout(TIMEOUT);
    }

    /** By name, not IP: the certificate names both, and host name checks must pass for either. */
    private String origin() {
        return "https://localhost:" + server.httpPort();
    }

    private Result login(String... args) {
        LoginCommand command = new LoginCommand();
        command.in = new ByteArrayInputStream((PASSWORD + "\n").getBytes(StandardCharsets.UTF_8));
        String[] withUser = new String[args.length + 3];
        System.arraycopy(args, 0, withUser, 0, args.length);
        withUser[args.length] = "--username";
        withUser[args.length + 1] = "alice";
        withUser[args.length + 2] = "--password-stdin";
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine cmd = new CommandLine(command);
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));
        int exitCode = cmd.execute(withUser);
        return new Result(exitCode, out.toString(), err.toString());
    }

    private record Result(int exitCode, String out, String err) {}

    /** Two distinct free ports; the server refuses port 0 because the dashboard is told both up front. */
    private static int[] freePorts() throws IOException {
        try (ServerSocket http = new ServerSocket(0);
                ServerSocket ws = new ServerSocket(0)) {
            return new int[] {http.getLocalPort(), ws.getLocalPort()};
        }
    }
}
