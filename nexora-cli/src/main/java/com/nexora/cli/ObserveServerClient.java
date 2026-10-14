package com.nexora.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import picocli.CommandLine.Model.CommandSpec;

/**
 * Shared plumbing for CLI commands that act on executions inside a running {@code nexora observe}
 * server ({@code cancel}, {@code replay}): argument validation, the HTTP call, and turning
 * connection failures into readable errors. Each command only decides how to report the response.
 */
final class ObserveServerClient {

    static final int EXIT_OK = 0;
    static final int EXIT_FAILED = 1;
    static final int EXIT_USAGE = 2;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    @FunctionalInterface
    interface ResponseReporter {
        /** Prints the outcome and returns the process exit code. */
        int report(HttpResponse<String> response, PrintWriter out, PrintWriter err);
    }

    private static final int STATUS_UNAUTHORIZED = 401;

    /**
     * Where to send the request and the API key to send with it. {@code apiKey} is the
     * {@code --api-key} value, falling back to {@value ApiKeyStore#ENV_VAR}; null sends none.
     */
    record Server(URI url, String apiKey) {

        static Server of(URI url, String flagKey) {
            String key = flagKey != null ? flagKey : System.getenv(ApiKeyStore.ENV_VAR);
            return new Server(url, key == null || key.isBlank() ? null : key.strip());
        }
    }

    private ObserveServerClient() {}

    /**
     * Sends {@code method} to {@code /api/executions/{executionId}{pathSuffix}} on {@code server}.
     *
     * @return the exit code from {@code reporter}, or {@link #EXIT_USAGE} / {@link #EXIT_FAILED} when
     *         the arguments are invalid or the server cannot be reached
     */
    static int call(
            CommandSpec spec,
            Server target,
            String executionId,
            String method,
            String pathSuffix,
            ResponseReporter reporter) {
        PrintWriter out = spec.commandLine().getOut();
        PrintWriter err = spec.commandLine().getErr();
        URI server = target.url();

        if (executionId.isBlank()) {
            err.println("Error: EXECUTION_ID must not be blank.");
            return EXIT_USAGE;
        }
        if (server.getScheme() == null
                || !ALLOWED_SCHEMES.contains(server.getScheme().toLowerCase(Locale.ROOT))
                || server.getHost() == null) {
            err.printf("Error: --server must be an http(s) URL such as http://localhost:9464, got: %s%n", server);
            return EXIT_USAGE;
        }

        URI uri = URI.create(stripTrailingSlash(server.toString())
                + ExecutionEndpoints.PATH_PREFIX
                + encodePathSegment(executionId)
                + pathSuffix);
        if (target.apiKey() != null && sendsKeyInClear(server)) {
            err.printf(
                    "Warning: sending the API key over plain HTTP to %s; anyone on the path can read it.%n",
                    server.getHost());
        }
        HttpResponse<String> response;
        try {
            response = send(uri, method, target.apiKey());
        } catch (IOException e) {
            err.printf(
                    "Error: could not reach the observe server at %s (%s). Is `nexora observe` running?%n",
                    server, describe(e));
            return EXIT_FAILED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            err.println("Error: interrupted while waiting for the observe server.");
            return EXIT_FAILED;
        }
        if (response.statusCode() == STATUS_UNAUTHORIZED) {
            err.println(
                    target.apiKey() == null
                            ? "Error: the observe server requires an API key. Set " + ApiKeyStore.ENV_VAR
                                    + " or pass --api-key."
                            : "Error: the observe server rejected the API key. Check " + ApiKeyStore.ENV_VAR
                                    + " or --api-key.");
            return EXIT_FAILED;
        }
        return reporter.report(response, out, err);
    }

    /** Plain HTTP to anything but this machine exposes the Bearer key on the network. */
    private static boolean sendsKeyInClear(URI server) {
        if (!"http".equalsIgnoreCase(server.getScheme())) {
            return false;
        }
        String host = server.getHost();
        return !("localhost".equalsIgnoreCase(host) || host.startsWith("127.") || "[::1]".equals(host));
    }

    /** Pulls the "error" field out of a JSON error body, falling back to the raw body. */
    static String errorMessage(String body) {
        return field(body, "error").orElse(body);
    }

    /** Reads a top-level text field from a JSON body. */
    static Optional<String> field(String body, String name) {
        try {
            JsonNode node = JSON.readTree(body).get(name);
            return node != null && node.isTextual() ? Optional.of(node.asText()) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static HttpResponse<String> send(URI target, String method, String apiKey)
            throws IOException, InterruptedException {
        HttpClient client =
                HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        HttpRequest.Builder request = HttpRequest.newBuilder(target)
                .timeout(REQUEST_TIMEOUT)
                .method(method, HttpRequest.BodyPublishers.noBody());
        if (apiKey != null) {
            request.header("Authorization", "Bearer " + apiKey);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** ConnectException and friends often carry no message; fall back to the exception type. */
    private static String describe(IOException e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String encodePathSegment(String value) {
        // URLEncoder targets form encoding; a path segment needs %20 for spaces, not '+'.
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
