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

    @BeforeEach
    void setUp() throws Exception {
        demo = LoginDemoApp.start(freePort(), freePort(), null);
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
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode(), path);
        }
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

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
