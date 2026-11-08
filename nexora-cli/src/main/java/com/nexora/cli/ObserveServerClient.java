package com.nexora.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexora.api.auth.ApiKeyStore;
import com.nexora.api.auth.TenantResolver;
import com.nexora.core.tenant.TenantId;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import javax.net.ssl.SSLHandshakeException;
import picocli.CommandLine.Model.CommandSpec;

/**
 * Shared plumbing for CLI commands that act on executions inside a running {@code nexora observe}
 * server ({@code cancel}, {@code replay}, {@code plugins reload}): argument validation, the HTTP call, and turning
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
     * Where to send the request and the key to send with it: {@code --api-key}, else
     * {@value ApiKeyStore#ENV_VAR}, else the user API key {@code nexora login} saved for this server.
     * Null sends none.
     *
     * @param tenantId       sent as {@value TenantResolver#HEADER} when not null, for a multi-tenant server
     * @param loginKeysApply whether a {@code nexora login} key can authorise this command, which
     *                       decides whether a {@code 401} suggests logging in
     */
    record Server(
            URI url,
            String apiKey,
            boolean allowInsecureHttp,
            HttpClient client,
            String tenantId,
            boolean loginKeysApply) {

        Server(URI url, String apiKey, boolean allowInsecureHttp, HttpClient client) {
            this(url, apiKey, allowInsecureHttp, client, null, true);
        }

        Server withTenant(String tenant) {
            return new Server(url, apiKey, allowInsecureHttp, client, tenant, loginKeysApply);
        }

        /**
         * @param credentialsFile where {@code nexora login} saved user keys; null for a command that
         *                        only the server API key may run, so no login key is looked up or suggested
         */
        static Server of(URI url, String flagKey, Path credentialsFile, boolean allowInsecureHttp, CaCertOption trust) {
            String key = flagKey != null ? flagKey : System.getenv(ApiKeyStore.ENV_VAR);
            boolean loginKeysApply = credentialsFile != null;
            if ((key == null || key.isBlank()) && loginKeysApply && isHttpUrl(url)) {
                key = new CliCredentials(credentialsFile).keyFor(url).orElse(null);
            }
            return new Server(
                    url,
                    key == null || key.isBlank() ? null : key.strip(),
                    allowInsecureHttp,
                    trust.client(CONNECT_TIMEOUT),
                    null,
                    loginKeysApply);
        }
    }

    static final String ALLOW_INSECURE_HTTP = "--allow-insecure-http";

    /**
     * Refuses to send {@code secret} (an API key or a password) over plain HTTP to another machine,
     * where anyone on the path can read it, unless the caller explicitly opted in. A warning is not
     * enough: once sent, a password stays exposed until it is changed.
     *
     * @return the error to print, or empty when sending is fine
     */
    static Optional<String> refuseCleartext(URI server, String secret, boolean allowInsecureHttp) {
        if (!sendsKeyInClear(server) || allowInsecureHttp) {
            return Optional.empty();
        }
        return Optional.of("Error: refusing to send the " + secret + " over plain HTTP to " + server.getHost()
                + ", where anyone on the network path can read it. Use an https:// URL (for example behind a"
                + " TLS proxy), or pass " + ALLOW_INSECURE_HTTP + " to accept the risk.");
    }

    static boolean isHttpUrl(URI server) {
        return server.getScheme() != null
                && ALLOWED_SCHEMES.contains(server.getScheme().toLowerCase(Locale.ROOT))
                && server.getHost() != null;
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
        if (executionId.isBlank()) {
            spec.commandLine().getErr().println("Error: EXECUTION_ID must not be blank.");
            return EXIT_USAGE;
        }
        return request(
                spec,
                target,
                ExecutionEndpoints.PATH_PREFIX + encodePathSegment(executionId) + pathSuffix,
                method,
                null,
                reporter);
    }

    /**
     * Sends {@code method} to {@code path} on {@code target}, with {@code jsonBody} when not null.
     *
     * @param path already-encoded path starting with {@code /}
     * @return the exit code from {@code reporter}, or {@link #EXIT_USAGE} / {@link #EXIT_FAILED} when
     *         the arguments are invalid or the server cannot be reached
     */
    static int request(
            CommandSpec spec, Server target, String path, String method, String jsonBody, ResponseReporter reporter) {
        PrintWriter out = spec.commandLine().getOut();
        PrintWriter err = spec.commandLine().getErr();
        URI server = target.url();

        if (target.tenantId() != null && !TenantId.isValid(target.tenantId())) {
            err.println("Error: --tenant must be 1 to " + TenantId.MAX_LENGTH
                    + " characters of a-z, 0-9, '-' or '_', starting with a letter or digit.");
            return EXIT_USAGE;
        }
        if (!isHttpUrl(server)) {
            err.printf("Error: --server must be an http(s) URL such as http://localhost:9464, got: %s%n", server);
            return EXIT_USAGE;
        }

        URI uri = URI.create(stripTrailingSlash(server.toString()) + path);
        if (target.apiKey() != null) {
            Optional<String> refusal = refuseCleartext(server, "API key", target.allowInsecureHttp());
            if (refusal.isPresent()) {
                err.println(refusal.get());
                return EXIT_USAGE;
            }
            if (sendsKeyInClear(server)) {
                err.printf(
                        "Warning: sending the API key over plain HTTP to %s (%s).%n",
                        server.getHost(), ALLOW_INSECURE_HTTP);
            }
        }
        HttpResponse<String> response;
        try {
            response = send(target, uri, method, jsonBody);
        } catch (IOException e) {
            err.println(unreachable(server, e));
            return EXIT_FAILED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            err.println("Error: interrupted while waiting for the observe server.");
            return EXIT_FAILED;
        }
        if (response.statusCode() == STATUS_UNAUTHORIZED) {
            err.println(unauthorized(target));
            return EXIT_FAILED;
        }
        return reporter.report(response, out, err);
    }

    /** What to do about a {@code 401}; logging in is suggested only where a login key would work. */
    static String unauthorized(Server target) {
        if (!target.loginKeysApply()) {
            return target.apiKey() == null
                    ? "Error: this command needs the observe server's API key. Set " + ApiKeyStore.ENV_VAR
                            + " or pass --api-key."
                    : "Error: the observe server rejected the API key. Check " + ApiKeyStore.ENV_VAR
                            + " / --api-key: this command needs the server's own key.";
        }
        return target.apiKey() == null
                ? "Error: the observe server requires an API key. Run `nexora login`, set " + ApiKeyStore.ENV_VAR
                        + ", or pass --api-key."
                : "Error: the observe server rejected the API key. A login key may have expired or been"
                        + " revoked: run `nexora login` again, or check " + ApiKeyStore.ENV_VAR + " / --api-key.";
    }

    /** Plain HTTP to anything but this machine exposes the Bearer key on the network. */
    static boolean sendsKeyInClear(URI server) {
        if (!"http".equalsIgnoreCase(server.getScheme())) {
            return false;
        }
        String host = server.getHost();
        return !("localhost".equalsIgnoreCase(host) || host.startsWith("127.") || "[::1]".equals(host));
    }

    /** Pulls the "error" field out of a JSON error body, falling back to the raw body. */
    /** The error text of a response: {@code {"error": "..."}} or {@code {"error": {"message": "..."}}}. */
    static String errorMessage(String body) {
        return field(body, "error").or(() -> nestedMessage(body)).orElse(body);
    }

    private static Optional<String> nestedMessage(String body) {
        try {
            JsonNode message = JSON.readTree(body).path("error").path("message");
            return message.isTextual() ? Optional.of(message.asText()) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
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

    private static HttpResponse<String> send(Server target, URI uri, String method, String jsonBody)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .method(
                        method,
                        jsonBody == null
                                ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
        if (jsonBody != null) {
            request.header("Content-Type", "application/json");
        }
        if (target.apiKey() != null) {
            request.header("Authorization", "Bearer " + target.apiKey());
        }
        if (target.tenantId() != null) {
            request.header(TenantResolver.HEADER, target.tenantId());
        }
        return target.client().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Why {@code server} could not be reached. A certificate the JVM does not trust is the usual
     * failure against a {@code nexora tls-gen} server, so that case names the fix.
     */
    static String unreachable(URI server, IOException e) {
        if (e instanceof SSLHandshakeException) {
            return "Error: could not verify the TLS certificate of " + server + " (" + describe(e) + "). If it"
                    + " comes from a private CA such as `nexora tls-gen`, pass --cacert with that CA's ca.pem.";
        }
        return "Error: could not reach the observe server at " + server + " (" + describe(e)
                + "). Is `nexora observe` running?";
    }

    /** ConnectException and friends often carry no message; fall back to the exception type. */
    private static String describe(IOException e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    static String encodePathSegment(String value) {
        // URLEncoder targets form encoding; a path segment needs %20 for spaces, not '+'.
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
