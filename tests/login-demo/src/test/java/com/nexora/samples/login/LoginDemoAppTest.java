package com.nexora.samples.login;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexora.api.auth.ApiAuth;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LoginDemoAppTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient client = HttpClient.newHttpClient();
    private LoginDemoApp.Running demo;

    private static final int START_ATTEMPTS = 3;

    /** Picks two distinct free ports and retries if another process takes one before the demo binds it. */
    @BeforeEach
    void setUp() throws Exception {
        for (int attempt = 1; ; attempt++) {
            int[] ports = twoFreePorts();
            try {
                demo = LoginDemoApp.start(ports[0], ports[1], null);
                return;
            } catch (java.net.BindException e) {
                if (attempt == START_ATTEMPTS) {
                    throw e;
                }
            }
        }
    }

    @AfterEach
    void tearDown() {
        if (demo != null) {
            demo.close();
        }
    }

    @Test
    void everyDemoUserCanSignInWithThePrintedPassword() throws Exception {
        for (LoginDemoApp.DemoUser user : LoginDemoApp.USERS) {
            HttpResponse<String> issued = login(user.username(), user.password());
            JsonNode body = JSON.readTree(issued.body());

            assertEquals(201, issued.statusCode(), user.username());
            assertEquals(user.username(), body.path("user").asText());
            assertTrue(body.path("apiKey").asText().startsWith(ApiAuth.USER_KEY_PREFIX));
        }
    }

    @Test
    void wrongPasswordIsRefused() throws Exception {
        assertEquals(401, login("alice", "not-the-demo-password").statusCode());
    }

    @Test
    void dashboardOffersPasswordSignIn() throws Exception {
        HttpResponse<String> page = client.send(
                HttpRequest.newBuilder(URI.create(base() + "/")).GET().build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(200, page.statusCode());
        assertTrue(
                page.body().contains("const PASSWORD_LOGIN = true;"), "the demo must show the username/password form");
    }

    @Test
    void aGeneratedServerKeyIsReportedAndWorks() throws Exception {
        HttpResponse<String> process = client.send(
                HttpRequest.newBuilder(URI.create(base() + "/api/process"))
                        .header("Authorization", "Bearer " + demo.serverKey())
                        .header("X-Tenant-ID", "acme")
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, process.statusCode());
    }

    @Test
    void historyAndDeadLetterPanelsHaveAStoreBehindThem() throws Exception {
        for (String path : java.util.List.of("/api/executions", "/api/dead-letters", "/api/schedules")) {
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create(base() + path))
                            .header("Authorization", "Bearer " + demo.serverKey())
                            .header("X-Tenant-ID", "acme")
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode(), path);
        }
    }

    @Test
    void demoUsersAreInDifferentTenantsAndNeverSeeEachOthersExecutions() throws Exception {
        String aliceKey = JSON.readTree(login("alice", "alice-demo-password").body())
                .path("apiKey")
                .asText();
        String bobKey = JSON.readTree(login("bob", "bob-demo-password").body())
                .path("apiKey")
                .asText();

        HttpResponse<String> started = client.send(
                HttpRequest.newBuilder(URI.create(base() + "/api/execute"))
                        .header("Authorization", "Bearer " + aliceKey)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"goal\":\"process order\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        String executionId = JSON.readTree(started.body()).path("executionId").asText();

        assertEquals(202, started.statusCode());
        assertEquals(404, getStatus("/api/executions/" + executionId, bobKey, null));
        assertEquals(403, getStatus("/api/executions", bobKey, "acme"));
        assertEquals(200, getStatus("/api/executions/" + executionId, aliceKey, null));
    }

    @Test
    void serverKeyMustNameATenant() throws Exception {
        assertEquals(400, getStatus("/api/executions", demo.serverKey(), null));
        assertEquals(200, getStatus("/api/executions", demo.serverKey(), "globex"));
    }

    /** Status of a GET with {@code key} as the Bearer credential, naming {@code tenant} when not null. */
    private int getStatus(String path, String key, String tenant) throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(URI.create(base() + path)).header("Authorization", "Bearer " + key);
        if (tenant != null) {
            request.header("X-Tenant-ID", tenant);
        }
        return client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString())
                .statusCode();
    }

    @Test
    void stoppingTheDemoDeletesItsUsersAndKeys() {
        Path dir = demo.dir();
        assertTrue(Files.exists(demo.usersFile()));

        demo.close();
        demo = null;

        assertFalse(Files.exists(dir));
    }

    private HttpResponse<String> login(String username, String password) throws Exception {
        String body = JSON.writeValueAsString(java.util.Map.of("username", username, "password", password));
        return client.send(
                HttpRequest.newBuilder(URI.create(base() + "/api/keys"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private String base() {
        return "http://" + LoginDemoApp.HOST + ":" + demo.server().httpPort();
    }

    /** Both sockets are held open together, so the two ports are always different. */
    private static int[] twoFreePorts() throws Exception {
        try (ServerSocket http = new ServerSocket(0);
                ServerSocket webSocket = new ServerSocket(0)) {
            return new int[] {http.getLocalPort(), webSocket.getLocalPort()};
        }
    }
}
