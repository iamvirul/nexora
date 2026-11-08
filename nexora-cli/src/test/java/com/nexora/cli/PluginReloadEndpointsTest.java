package com.nexora.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import com.nexora.core.capability.CapabilityResult;
import com.nexora.core.tenant.TenantId;
import com.nexora.persistence.jdbc.JdbcExecutionStore;
import com.nexora.spi.Capability;
import com.nexora.spi.CapabilityDescriptor;
import com.nexora.spi.CapabilityProvider;
import com.nexora.spi.NexoraPlugin;
import com.nexora.spi.PluginContext;
import com.nexora.spi.PluginDescriptor;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
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
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** {@code POST /api/plugins/{id}/reload} and {@code nexora plugins reload}, against a real engine and store. */
class PluginReloadEndpointsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String LOOPBACK = "127.0.0.1";
    private static final String PLUGIN_ID = "http-greeter";
    private static final String PASSWORD = "correct horse battery";
    private static final String SERVICE_FILE = "META-INF/services/" + NexoraPlugin.class.getName();

    @TempDir
    Path dir;

    private final HttpClient client = HttpClient.newHttpClient();
    private Path pluginDir;
    private NexoraEngine engine;
    private NexoraObservability observability;
    private HttpServer server;

    @BeforeEach
    void setUp() throws IOException {
        pluginDir = Files.createDirectories(dir.resolve("plugins"));
        engine = NexoraEngine.builder()
                .withExecutionStore(JdbcExecutionStore.h2InMemory())
                .withPluginJar(jar(pluginDir, "greeter-1.jar", GreeterV1.class))
                .withPlugin(new InlinePlugin())
                .build();
        server = start(engine);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        observability.close();
        engine.close();
    }

    @Test
    void reloadSwapsPluginAndReturnsBothVersions() throws Exception {
        Path v2 = jar(pluginDir, "greeter-2.jar", GreeterV2.class);

        HttpResponse<String> response = reload(PLUGIN_ID, v2.toString());

        assertEquals(200, response.statusCode(), response.body());
        JsonNode body = JSON.readTree(response.body());
        assertEquals("1.0.0", body.get("previousVersion").asText());
        assertEquals("2.0.0", body.get("newVersion").asText());
        assertEquals(v2.toRealPath(), engine.pluginJar(PLUGIN_ID).orElseThrow());
    }

    @Test
    void failedReloadReturns422AndKeepsOldVersion() throws Exception {
        Path broken = jar(pluginDir, "greeter-broken.jar", BrokenGreeter.class);

        HttpResponse<String> response = reload(PLUGIN_ID, broken.toString());

        assertEquals(422, response.statusCode());
        assertEquals("PLUGIN_RELOAD_FAILED", errorCode(response));
        assertTrue(errorMessage(response).contains("1.0.0 is still active"), response.body());
        assertTrue(engine.pluginJar(PLUGIN_ID).orElseThrow().endsWith("greeter-1.jar"));
    }

    @Test
    void jarOutsideThePluginDirectoryIsRefused() throws Exception {
        Path elsewhere = jar(Files.createDirectories(dir.resolve("elsewhere")), "greeter-2.jar", GreeterV2.class);

        HttpResponse<String> response = reload(PLUGIN_ID, elsewhere.toString());

        assertEquals(400, response.statusCode());
        assertEquals("JAR_NOT_ALLOWED", errorCode(response));
    }

    @Test
    void symlinkInPluginDirectoryPointingElsewhereIsRefused() throws Exception {
        Path elsewhere = jar(Files.createDirectories(dir.resolve("elsewhere")), "greeter-2.jar", GreeterV2.class);
        Path link = Files.createSymbolicLink(pluginDir.resolve("linked.jar"), elsewhere);

        HttpResponse<String> response = reload(PLUGIN_ID, link.toString());

        assertEquals(400, response.statusCode());
        assertEquals("JAR_NOT_ALLOWED", errorCode(response));
    }

    @Test
    void relativeJarPathIsRefused() throws Exception {
        HttpResponse<String> response = reload(PLUGIN_ID, "greeter-2.jar");

        assertEquals(400, response.statusCode());
        assertEquals("INVALID_JAR_PATH", errorCode(response));
    }

    @Test
    void nonJarFileIsRefused() throws Exception {
        Path notJar = Files.writeString(pluginDir.resolve("notes.txt"), "hello");

        HttpResponse<String> response = reload(PLUGIN_ID, notJar.toString());

        assertEquals(400, response.statusCode());
        assertEquals("INVALID_JAR_PATH", errorCode(response));
    }

    @Test
    void missingJarFieldIsRefused() throws Exception {
        HttpResponse<String> response = send(PLUGIN_ID, "{}", TestAuth.KEY);

        assertEquals(400, response.statusCode());
        assertEquals("INVALID_REQUEST", errorCode(response));
    }

    @Test
    void malformedBodyIsRefused() throws Exception {
        HttpResponse<String> response = send(PLUGIN_ID, "not json", TestAuth.KEY);

        assertEquals(400, response.statusCode());
        assertEquals("INVALID_REQUEST", errorCode(response));
    }

    @Test
    void unknownPluginReturns404() throws Exception {
        HttpResponse<String> response =
                reload("nobody", pluginDir.resolve("x.jar").toString());

        assertEquals(404, response.statusCode());
        assertEquals("PLUGIN_NOT_FOUND", errorCode(response));
    }

    @Test
    void pluginRegisteredInCodeReturns409() throws Exception {
        HttpResponse<String> response =
                reload(InlinePlugin.ID, pluginDir.resolve("x.jar").toString());

        assertEquals(409, response.statusCode());
        assertEquals("PLUGIN_NOT_RELOADABLE", errorCode(response));
    }

    @Test
    void userKeyIsForbidden() throws Exception {
        String userKey = JSON.readTree(login().body()).get("apiKey").asText();
        Path v2 = jar(pluginDir, "greeter-2.jar", GreeterV2.class);

        HttpResponse<String> response = send(PLUGIN_ID, body(v2.toString()), userKey);

        assertEquals(403, response.statusCode());
        assertEquals("FORBIDDEN", errorCode(response));
        assertTrue(engine.pluginJar(PLUGIN_ID).orElseThrow().endsWith("greeter-1.jar"));
    }

    @Test
    void unauthenticatedRequestReturns401() throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(reloadUri(PLUGIN_ID))
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(401, response.statusCode());
    }

    @Test
    void reloadRouteOnlyAcceptsPost() throws Exception {
        HttpResponse<String> response = client.send(
                TestAuth.authorized(reloadUri(PLUGIN_ID)).GET().build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(405, response.statusCode());
        assertEquals("POST", response.headers().firstValue("Allow").orElse(null));
    }

    @Test
    void reloadWithoutPersistenceReturns503() throws Exception {
        try (NexoraEngine stateless = NexoraEngine.builder()
                .withPluginJar(jar(pluginDir, "stateless-1.jar", GreeterV1.class))
                .build()) {
            NexoraObservability previous = observability;
            HttpServer bare = start(stateless);
            try {
                HttpResponse<String> response = client.send(
                        TestAuth.authorized(URI.create(url(bare)
                                        + PluginEndpoints.PATH_PREFIX
                                        + PLUGIN_ID
                                        + PluginEndpoints.RELOAD_SUFFIX))
                                .POST(HttpRequest.BodyPublishers.ofString(body("/x.jar")))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(503, response.statusCode());
                assertEquals("PERSISTENCE_DISABLED", errorCode(response));
            } finally {
                bare.stop(0);
                observability.close();
                observability = previous;
            }
        }
    }

    @Test
    void reloadCommandReportsBothVersions() throws Exception {
        Path v2 = jar(pluginDir, "greeter-2.jar", GreeterV2.class);

        CommandResult result = runCommand(PLUGIN_ID, "--jar", v2.toString(), "--server", url(server));

        assertEquals(ObserveServerClient.EXIT_OK, result.exitCode(), result.err());
        assertTrue(result.out().contains("Reloaded plugin " + PLUGIN_ID + ": 1.0.0 -> 2.0.0"), result.out());
    }

    @Test
    void reloadCommandReportsRollback() throws Exception {
        Path broken = jar(pluginDir, "greeter-broken.jar", BrokenGreeter.class);

        CommandResult result = runCommand(PLUGIN_ID, "--jar", broken.toString(), "--server", url(server));

        assertEquals(ObserveServerClient.EXIT_FAILED, result.exitCode());
        assertTrue(result.err().contains("1.0.0 is still active"), result.err());
    }

    @Test
    void reloadCommandReportsUnknownPlugin() throws Exception {
        CommandResult result =
                runCommand("nobody", "--jar", pluginDir.resolve("x.jar").toString(), "--server", url(server));

        assertEquals(ObserveServerClient.EXIT_FAILED, result.exitCode());
        assertTrue(result.err().contains("no active plugin with id nobody"), result.err());
    }

    @Test
    void reloadCommandRequiresJarOption() {
        CommandResult result = runCommand(PLUGIN_ID, "--server", url(server));

        assertEquals(ObserveServerClient.EXIT_USAGE, result.exitCode());
    }

    private HttpServer start(NexoraEngine target) throws IOException {
        UserStore users = new UserStore(dir.resolve("users-" + System.nanoTime()), new PasswordHasher());
        users.add("alice", PASSWORD.toCharArray(), TenantId.DEFAULT);
        observability = NexoraObservability.attach(target);
        HttpServer http = HttpServer.create(new InetSocketAddress(LOOPBACK, 0), 0);
        ObserveCommand.registerRoutes(
                http, target, observability, new byte[0], new ApiAuth(ApiKeyStore.digest(TestAuth.KEY), users));
        http.start();
        return http;
    }

    private HttpResponse<String> reload(String pluginId, String jarPath) throws Exception {
        return send(pluginId, body(jarPath), TestAuth.KEY);
    }

    private HttpResponse<String> send(String pluginId, String json, String key) throws Exception {
        return client.send(
                HttpRequest.newBuilder(reloadUri(pluginId))
                        .header("Authorization", "Bearer " + key)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> login() throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create(url(server) + SessionEndpoints.KEYS_PATH))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"username\":\"alice\",\"password\":\"" + PASSWORD + "\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private URI reloadUri(String pluginId) {
        return URI.create(url(server) + PluginEndpoints.PATH_PREFIX + pluginId + PluginEndpoints.RELOAD_SUFFIX);
    }

    private static String url(HttpServer http) {
        return "http://" + LOOPBACK + ":" + http.getAddress().getPort();
    }

    private static String body(String jarPath) throws IOException {
        return JSON.writeValueAsString(Map.of(PluginEndpoints.JAR_FIELD, jarPath));
    }

    private static String errorCode(HttpResponse<String> response) throws IOException {
        return JSON.readTree(response.body()).path("error").path("code").asText();
    }

    private static String errorMessage(HttpResponse<String> response) throws IOException {
        return JSON.readTree(response.body()).path("error").path("message").asText();
    }

    private static Path jar(Path directory, String name, Class<? extends NexoraPlugin> pluginClass) throws IOException {
        Path jar = directory.resolve(name);
        try (OutputStream out = Files.newOutputStream(jar);
                JarOutputStream jarOut = new JarOutputStream(out)) {
            jarOut.putNextEntry(new JarEntry(SERVICE_FILE));
            jarOut.write(pluginClass.getName().getBytes(StandardCharsets.UTF_8));
            jarOut.closeEntry();
        }
        return jar;
    }

    private static CommandResult runCommand(String... args) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine cmd = new CommandLine(new PluginsReloadCommand());
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));
        String[] withKey = java.util.Arrays.copyOf(args, args.length + 2);
        withKey[args.length] = "--api-key";
        withKey[args.length + 1] = TestAuth.KEY;
        int exitCode = cmd.execute(withKey);
        return new CommandResult(exitCode, out.toString(), err.toString());
    }

    private record CommandResult(int exitCode, String out, String err) {}

    abstract static class Greeter implements NexoraPlugin {
        private final String version;

        Greeter(String version) {
            this.version = version;
        }

        @Override
        public PluginDescriptor descriptor() {
            return new PluginDescriptor(PLUGIN_ID, version, "Greets", List.of(), null);
        }

        @Override
        public void initialize(PluginContext context) {
            // no state to set up
        }

        @Override
        public List<CapabilityProvider> capabilityProviders() {
            return List.of(new CapabilityProvider() {
                @Override
                public CapabilityDescriptor descriptor() {
                    return new CapabilityDescriptor("http_greet", "Greet", List.of(), List.of(), true, false);
                }

                @Override
                public Capability create(PluginContext context) {
                    return request -> CapabilityResult.success("hello from " + version);
                }
            });
        }

        @Override
        public void shutdown() {
            // nothing to release
        }
    }

    public static final class GreeterV1 extends Greeter {
        public GreeterV1() {
            super("1.0.0");
        }
    }

    public static final class GreeterV2 extends Greeter {
        public GreeterV2() {
            super("2.0.0");
        }
    }

    public static final class BrokenGreeter extends Greeter {
        public BrokenGreeter() {
            super("3.0.0");
        }

        @Override
        public void initialize(PluginContext context) {
            throw new IllegalStateException("config missing");
        }
    }

    static final class InlinePlugin implements NexoraPlugin {
        static final String ID = "inline-plugin";

        @Override
        public PluginDescriptor descriptor() {
            return new PluginDescriptor(ID, "1.0.0", "Registered in code", List.of(), null);
        }

        @Override
        public void initialize(PluginContext context) {
            // no state to set up
        }

        @Override
        public List<CapabilityProvider> capabilityProviders() {
            return List.of();
        }

        @Override
        public void shutdown() {
            // nothing to release
        }
    }
}
