package com.nexora.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ApiDocsEndpointsTest {

    private static final String LOOPBACK = "127.0.0.1";

    private final HttpClient client = HttpClient.newHttpClient();
    private HttpServer server;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(LOOPBACK, 0), 0);
        ApiDocsEndpoints.load().register(server);
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void servesOpenApiDocumentAsYaml() throws Exception {
        HttpResponse<String> response = get("/openapi.yaml");

        assertEquals(200, response.statusCode());
        assertTrue(contentType(response).startsWith("application/yaml"), contentType(response));
        assertTrue(
                response.body().startsWith("openapi: 3.1.0"),
                response.body().lines().findFirst().orElse(""));
    }

    @Test
    void docsPageLoadsSwaggerUiFromLocalAssetsUnderStrictCsp() throws Exception {
        HttpResponse<String> response = get("/docs");

        assertEquals(200, response.statusCode());
        assertTrue(contentType(response).startsWith("text/html"));
        assertTrue(response.body().contains("src=\"/docs/swagger-ui-bundle.js\""));
        assertTrue(
                !response.body().contains("http://") && !response.body().contains("https://"),
                "docs page must not reference external URLs");
        String csp = response.headers().firstValue("Content-Security-Policy").orElse("");
        assertTrue(csp.contains("default-src 'self'") && csp.contains("script-src 'self'"), csp);
    }

    @Test
    void docsPageWithTrailingSlashIsTheSamePage() throws Exception {
        assertEquals(get("/docs").body(), get("/docs/").body());
    }

    @Test
    void servesBundledSwaggerUiAssetsWithTheirContentTypes() throws Exception {
        assertAsset("/docs/swagger-ui-bundle.js", "text/javascript");
        assertAsset("/docs/swagger-ui.css", "text/css");
        assertAsset("/docs/favicon-32x32.png", "image/png");
    }

    @Test
    void initScriptPointsAtLocalDocumentAndDisablesRemoteValidator() throws Exception {
        String script = get("/docs/swagger-ui-init.js").body();

        assertTrue(script.contains("url: '/openapi.yaml'"), script);
        assertTrue(script.contains("validatorUrl: null"), script);
    }

    @Test
    void onlyAllowlistedAssetsAreServed() throws Exception {
        assertEquals(404, get("/docs/index.html").statusCode());
        assertEquals(404, get("/docs/swagger-ui-es-bundle.js").statusCode());
        assertEquals(404, get("/docs/..%2Fopenapi.yaml").statusCode());
    }

    @Test
    void pathsThatOnlySharePrefixAre404() throws Exception {
        assertEquals(404, get("/docsx").statusCode());
        assertEquals(404, get("/openapi.yamlx").statusCode());
    }

    @Test
    void nonGetIsRejectedWithAllowHeader() throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(uri("/openapi.yaml"))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(405, response.statusCode());
        assertEquals("GET", response.headers().firstValue("Allow").orElse(null));
    }

    private void assertAsset(String path, String expectedType) throws Exception {
        HttpResponse<byte[]> response =
                client.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, response.statusCode(), path);
        assertTrue(response.body().length > 0, path);
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith(expectedType), path);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://" + LOOPBACK + ":" + server.getAddress().getPort() + path);
    }

    private static String contentType(HttpResponse<?> response) {
        return response.headers().firstValue("Content-Type").orElse("");
    }
}
