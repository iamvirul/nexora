package com.nexora.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexora.api.auth.SessionEndpoints;
import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * Trades a dashboard username and password for a user API key and saves it, so {@code nexora
 * cancel} and {@code nexora replay} act as that user without the server's own key.
 */
@Command(
        name = "login",
        // description loaded from help/login.help at startup via HelpLoader
        mixinStandardHelpOptions = true)
public class LoginCommand implements Callable<Integer> {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final int STATUS_CREATED = 201;
    private static final int STATUS_UNAUTHORIZED = 401;
    private static final int STATUS_TOO_MANY_REQUESTS = 429;

    @Spec
    CommandSpec spec;

    @Option(
            names = {"--server"},
            defaultValue = "http://localhost:9464",
            description = "Base URL of the observe server. Default: ${DEFAULT-VALUE}")
    URI server;

    @Option(
            names = {"-u", "--username"},
            description = "Dashboard username. Prompted for when omitted.")
    String username;

    @Option(names = "--password-stdin", description = "Read the password from the first line of standard input.")
    boolean passwordStdin;

    @Option(
            names = {ObserveServerClient.ALLOW_INSECURE_HTTP},
            description = "Send the password over plain HTTP to another machine anyway (it can be read on the way).")
    boolean allowInsecureHttp;

    @Option(
            names = {"--credentials-file"},
            defaultValue = CliCredentials.DEFAULT_FILE,
            description = "Where the user API key is saved. Default: ${DEFAULT-VALUE}")
    Path credentialsFile;

    /** Where --password-stdin reads from; tests replace it. */
    InputStream in = System.in;

    @Override
    public Integer call() {
        PrintWriter out = spec.commandLine().getOut();
        PrintWriter err = spec.commandLine().getErr();
        if (!ObserveServerClient.isHttpUrl(server)) {
            err.printf("Error: --server must be an http(s) URL such as http://localhost:9464, got: %s%n", server);
            return ObserveServerClient.EXIT_USAGE;
        }
        // Before prompting: never ask for a password that will not be sent.
        Optional<String> refusal = ObserveServerClient.refuseCleartext(server, "password", allowInsecureHttp);
        if (refusal.isPresent()) {
            err.println(refusal.get());
            return ObserveServerClient.EXIT_USAGE;
        }
        char[] password = null;
        try {
            String name = username != null ? username : prompt("Username: ");
            password = readPassword();
            HttpResponse<String> response = requestKey(name, password);
            return report(response, out, err);
        } catch (IllegalArgumentException e) {
            err.println("Error: " + e.getMessage());
            return ObserveServerClient.EXIT_USAGE;
        } catch (IOException e) {
            err.printf(
                    "Error: could not reach the observe server at %s (%s). Is `nexora observe` running?%n", server, e);
            return ObserveServerClient.EXIT_FAILED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            err.println("Error: interrupted while waiting for the observe server.");
            return ObserveServerClient.EXIT_FAILED;
        } finally {
            if (password != null) Arrays.fill(password, '\0');
        }
    }

    private int report(HttpResponse<String> response, PrintWriter out, PrintWriter err) throws IOException {
        switch (response.statusCode()) {
            case STATUS_CREATED -> {
                JsonNode body = JSON.readTree(response.body());
                new CliCredentials(credentialsFile)
                        .save(server, body.path("apiKey").asText());
                out.printf(
                        "Logged in to %s as %s. Key saved to %s, valid until %s.%n",
                        server,
                        body.path("user").asText(),
                        credentialsFile,
                        body.path("expiresAt").asText());
                out.println("`nexora cancel` and `nexora replay` will use it; `nexora logout` revokes it.");
                return ObserveServerClient.EXIT_OK;
            }
            case STATUS_UNAUTHORIZED -> err.println("Error: wrong username or password.");
            case STATUS_TOO_MANY_REQUESTS ->
                err.printf(
                        "Error: too many login attempts; try again in %s seconds.%n",
                        response.headers().firstValue("Retry-After").orElse("a few"));
            default ->
                err.printf("Error: observe server returned HTTP %d: %s%n", response.statusCode(), response.body());
        }
        return ObserveServerClient.EXIT_FAILED;
    }

    private HttpResponse<String> requestKey(String name, char[] password) throws IOException, InterruptedException {
        Map<String, String> credentials = new LinkedHashMap<>();
        credentials.put("username", name);
        credentials.put("password", new String(password));
        URI target = URI.create(ObserveServerClient.stripTrailingSlash(server.toString()) + SessionEndpoints.KEYS_PATH);
        HttpRequest request = HttpRequest.newBuilder(target)
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(credentials)))
                .build();
        return HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .build()
                .send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String prompt(String label) throws IOException {
        Console console = System.console();
        if (console == null) {
            throw new IllegalArgumentException("No terminal to prompt on; pass --username");
        }
        String value = console.readLine(label);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Username is required");
        }
        return value.strip();
    }

    private char[] readPassword() throws IOException {
        if (passwordStdin) {
            String line = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)).readLine();
            if (line == null || line.isEmpty()) {
                throw new IllegalArgumentException("No password on standard input");
            }
            return line.toCharArray();
        }
        Console console = System.console();
        if (console == null) {
            throw new IllegalArgumentException(
                    "No terminal to prompt for a password; pipe it in with --password-stdin");
        }
        char[] password = console.readPassword("Password: ");
        if (password == null || password.length == 0) {
            throw new IllegalArgumentException("Password is required");
        }
        return password;
    }
}
