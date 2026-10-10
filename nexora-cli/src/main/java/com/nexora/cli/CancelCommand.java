package com.nexora.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * Cancels an execution running inside a {@code nexora observe} server.
 *
 * <p>Executions live in the memory of the process that started them, so this command cannot
 * build its own engine like {@code run} does; it calls {@code DELETE /api/executions/{id}} on the
 * observe server instead.
 */
@Command(
        name = "cancel",
        // description loaded from help/cancel.help at startup via HelpLoader
        mixinStandardHelpOptions = true
)
public class CancelCommand implements Callable<Integer> {

    static final int EXIT_OK = 0;
    static final int EXIT_FAILED = 1;
    static final int EXIT_USAGE = 2;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    @Spec
    CommandSpec spec;

    @Parameters(index = "0", paramLabel = "EXECUTION_ID", description = "ID of the execution to cancel.")
    private String executionId;

    @Option(names = {"--server"}, defaultValue = "http://localhost:9464",
            description = "Base URL of the running observe server. Default: ${DEFAULT-VALUE}")
    private URI server;

    @Override
    public Integer call() {
        PrintWriter out = spec.commandLine().getOut();
        PrintWriter err = spec.commandLine().getErr();

        if (executionId.isBlank()) {
            err.println("Error: EXECUTION_ID must not be blank.");
            return EXIT_USAGE;
        }
        if (server.getScheme() == null || !ALLOWED_SCHEMES.contains(server.getScheme().toLowerCase())
                || server.getHost() == null) {
            err.printf("Error: --server must be an http(s) URL such as http://localhost:9464, got: %s%n", server);
            return EXIT_USAGE;
        }

        URI target = URI.create(stripTrailingSlash(server.toString())
                + ExecutionEndpoints.PATH_PREFIX + encodePathSegment(executionId));
        HttpResponse<String> response;
        try {
            response = send(target);
        } catch (IOException e) {
            err.printf("Error: could not reach the observe server at %s (%s). Is `nexora observe` running?%n",
                    server, describe(e));
            return EXIT_FAILED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            err.println("Error: interrupted while waiting for the observe server.");
            return EXIT_FAILED;
        }
        return report(response, out, err);
    }

    private static HttpResponse<String> send(URI target) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        HttpRequest request = HttpRequest.newBuilder(target)
                .timeout(REQUEST_TIMEOUT)
                .DELETE()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private int report(HttpResponse<String> response, PrintWriter out, PrintWriter err) {
        switch (response.statusCode()) {
            case 200 -> {
                out.printf("Cancellation requested for execution %s.%n", executionId);
                return EXIT_OK;
            }
            case 404 -> err.printf("Error: no execution found with id %s.%n", executionId);
            case 409 -> err.printf("Error: execution %s could not be cancelled: %s.%n",
                    executionId, errorMessage(response.body()));
            default -> err.printf("Error: observe server returned HTTP %d: %s%n",
                    response.statusCode(), errorMessage(response.body()));
        }
        return EXIT_FAILED;
    }

    /** Pulls the "error" field out of a JSON error body, falling back to the raw body. */
    private static String errorMessage(String body) {
        try {
            JsonNode error = JSON.readTree(body).get("error");
            return error != null && error.isTextual() ? error.asText() : body;
        } catch (IOException e) {
            return body;
        }
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
