package com.nexora.cli;

import com.nexora.api.auth.SessionEndpoints;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/** Revokes the saved user API key on the server and deletes it locally. */
@Command(
        name = "logout",
        // description loaded from help/logout.help at startup via HelpLoader
        mixinStandardHelpOptions = true)
public class LogoutCommand implements Callable<Integer> {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    @Spec
    CommandSpec spec;

    @Option(
            names = {"--server"},
            defaultValue = "http://localhost:9464",
            description = "Base URL of the observe server. Default: ${DEFAULT-VALUE}")
    URI server;

    @Option(
            names = {"--credentials-file"},
            defaultValue = CliCredentials.DEFAULT_FILE,
            description = "Where `nexora login` saved the key. Default: ${DEFAULT-VALUE}")
    Path credentialsFile;

    @Override
    public Integer call() {
        PrintWriter out = spec.commandLine().getOut();
        PrintWriter err = spec.commandLine().getErr();
        if (!ObserveServerClient.isHttpUrl(server)) {
            err.printf("Error: --server must be an http(s) URL such as http://localhost:9464, got: %s%n", server);
            return ObserveServerClient.EXIT_USAGE;
        }
        CliCredentials credentials = new CliCredentials(credentialsFile);
        Optional<String> key;
        try {
            key = credentials.keyFor(server);
        } catch (IllegalStateException e) {
            err.println("Error: " + e.getMessage());
            return ObserveServerClient.EXIT_FAILED;
        }
        if (key.isEmpty()) {
            out.printf("Not logged in to %s.%n", server);
            return ObserveServerClient.EXIT_OK;
        }
        // Forget the key locally even if the server is down: it expires there on its own.
        boolean revoked = revokeOnServer(key.get());
        try {
            credentials.remove(server);
        } catch (IOException e) {
            err.printf("Error: could not update %s (%s)%n", credentialsFile, e.getMessage());
            return ObserveServerClient.EXIT_FAILED;
        }
        out.printf(
                revoked
                        ? "Logged out of %s.%n"
                        : "Removed the saved key for %s; the server could not be reached to revoke it, so it stays valid until it expires.%n",
                server);
        return ObserveServerClient.EXIT_OK;
    }

    private boolean revokeOnServer(String key) {
        URI target = URI.create(ObserveServerClient.stripTrailingSlash(server.toString()) + SessionEndpoints.PATH);
        HttpRequest request = HttpRequest.newBuilder(target)
                .timeout(TIMEOUT)
                .header("Authorization", "Bearer " + key)
                .DELETE()
                .build();
        try {
            HttpResponse<Void> response = HttpClient.newBuilder()
                    .connectTimeout(TIMEOUT)
                    .build()
                    .send(request, HttpResponse.BodyHandlers.discarding());
            // 401 means the key had already expired or been revoked: nothing left to revoke.
            return response.statusCode() == 200 || response.statusCode() == 401;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
