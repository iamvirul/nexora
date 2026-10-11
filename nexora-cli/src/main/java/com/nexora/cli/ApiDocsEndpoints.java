package com.nexora.cli;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/**
 * Serves the observe server's API contract: {@code GET /openapi.yaml} (the hand-maintained
 * OpenAPI 3.1 document) and {@code GET /docs} (Swagger UI).
 *
 * <p>Swagger UI's files come from the {@code org.webjars:swagger-ui} jar on the classpath, so the
 * page makes no CDN requests. Assets are served only from a fixed allowlist, never from a path the
 * client controls, and the docs page carries a Content-Security-Policy that blocks any external load.
 */
final class ApiDocsEndpoints {

    static final String SPEC_PATH = "/openapi.yaml";
    static final String DOCS_PATH = "/docs";
    static final String ASSET_PREFIX = DOCS_PATH + "/";

    private static final String SPEC_RESOURCE = "openapi.yaml";
    private static final String DOCS_PAGE_RESOURCE = "docs/index.html";
    private static final String INIT_SCRIPT_RESOURCE = "docs/swagger-ui-init.js";
    private static final String SWAGGER_UI_POM = "META-INF/maven/org.webjars/swagger-ui/pom.properties";
    private static final String SWAGGER_UI_ROOT = "META-INF/resources/webjars/swagger-ui/";

    private static final String YAML = "application/yaml; charset=utf-8";
    private static final String HTML = "text/html; charset=utf-8";
    private static final String CSS = "text/css; charset=utf-8";
    private static final String JAVASCRIPT = "text/javascript; charset=utf-8";
    private static final String PNG = "image/png";

    // Swagger UI injects inline styles and data: images; scripts and requests stay same-origin.
    private static final String DOCS_CSP = "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
            + "img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'";

    private static final int STATUS_OK = 200;
    private static final int STATUS_NOT_FOUND = 404;
    private static final int STATUS_METHOD_NOT_ALLOWED = 405;

    private record Asset(String contentType, byte[] body) {}

    private final byte[] spec;
    private final byte[] docsPage;
    private final Map<String, Asset> assets;

    private ApiDocsEndpoints(byte[] spec, byte[] docsPage, Map<String, Asset> assets) {
        this.spec = spec;
        this.docsPage = docsPage;
        this.assets = Map.copyOf(assets);
    }

    /**
     * Loads every file up front, so a packaging mistake fails at server startup instead of on the
     * first request to {@code /docs}.
     *
     * @throws IllegalStateException if the spec, docs page or a Swagger UI asset is missing
     */
    static ApiDocsEndpoints load() {
        String swaggerUi = SWAGGER_UI_ROOT + swaggerUiVersion() + "/";
        Map<String, Asset> assets = new HashMap<>();
        assets.put("swagger-ui.css", new Asset(CSS, read(swaggerUi + "swagger-ui.css")));
        assets.put("swagger-ui-bundle.js", new Asset(JAVASCRIPT, read(swaggerUi + "swagger-ui-bundle.js")));
        assets.put("favicon-32x32.png", new Asset(PNG, read(swaggerUi + "favicon-32x32.png")));
        assets.put("swagger-ui-init.js", new Asset(JAVASCRIPT, read(INIT_SCRIPT_RESOURCE)));
        return new ApiDocsEndpoints(read(SPEC_RESOURCE), read(DOCS_PAGE_RESOURCE), assets);
    }

    void register(HttpServer server) {
        server.createContext(SPEC_PATH, getOnly(this::spec));
        server.createContext(DOCS_PATH, getOnly(this::docsPage));
        server.createContext(ASSET_PREFIX, getOnly(this::asset));
    }

    private void spec(HttpExchange exchange) throws IOException {
        if (!SPEC_PATH.equals(exchange.getRequestURI().getPath())) {
            notFound(exchange);
            return;
        }
        ObserveCommand.send(exchange, STATUS_OK, YAML, spec);
    }

    private void docsPage(HttpExchange exchange) throws IOException {
        if (!DOCS_PATH.equals(exchange.getRequestURI().getPath())) {
            notFound(exchange);
            return;
        }
        serveDocsPage(exchange);
    }

    private void asset(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.equals(ASSET_PREFIX)) { // "/docs/" with a trailing slash is the same page
            serveDocsPage(exchange);
            return;
        }
        Asset asset = assets.get(path.substring(ASSET_PREFIX.length()));
        if (asset == null) {
            notFound(exchange);
            return;
        }
        ObserveCommand.send(exchange, STATUS_OK, asset.contentType(), asset.body());
    }

    private void serveDocsPage(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Content-Security-Policy", DOCS_CSP);
        ObserveCommand.send(exchange, STATUS_OK, HTML, docsPage);
    }

    private static HttpHandler getOnly(HttpHandler handler) {
        return exchange -> {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "GET");
                ObserveCommand.sendText(exchange, STATUS_METHOD_NOT_ALLOWED, "Method Not Allowed");
                return;
            }
            handler.handle(exchange);
        };
    }

    private static void notFound(HttpExchange exchange) throws IOException {
        ObserveCommand.sendText(exchange, STATUS_NOT_FOUND, "Not Found");
    }

    private static String swaggerUiVersion() {
        Properties pom = new Properties();
        try (InputStream in = openResource(SWAGGER_UI_POM)) {
            pom.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + SWAGGER_UI_POM, e);
        }
        return Objects.requireNonNull(pom.getProperty("version"), "No version in " + SWAGGER_UI_POM);
    }

    private static byte[] read(String resource) {
        try (InputStream in = openResource(resource)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + resource, e);
        }
    }

    private static InputStream openResource(String resource) {
        InputStream in = ApiDocsEndpoints.class.getClassLoader().getResourceAsStream(resource);
        if (in == null) {
            throw new IllegalStateException("Missing classpath resource " + resource);
        }
        return in;
    }
}
