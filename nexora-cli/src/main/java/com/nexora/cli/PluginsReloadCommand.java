package com.nexora.cli;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexora.api.auth.ApiKeyStore;
import java.io.PrintWriter;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Swaps a plugin JAR inside a running {@code nexora observe} server without restarting it.
 *
 * <p>Plugins live in the server's engine, so this calls {@code POST /api/plugins/{id}/reload}
 * there. The JAR path is read on the server: a relative path is made absolute against this
 * command's working directory, which only matches when both run on the same machine.
 */
@Command(
        name = "reload",
        mixinStandardHelpOptions = true,
        description = {
            "Swap a plugin for a new JAR inside a running `nexora observe` server, without a restart.",
            "",
            "New executions use the new version at once; steps already running finish on the old one.",
            "If the new JAR fails to load or initialise, the old version keeps serving.",
            "Requires the server API key, and the new JAR must sit in the same directory on the server",
            "as the plugin's current JAR. The server must run with an execution store.",
            "",
            "Example:",
            "  nexora plugins reload payments --jar /opt/nexora/plugins/payments-1.3.0.jar"
        })
public class PluginsReloadCommand implements Callable<Integer> {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Spec
    CommandSpec spec;

    @Parameters(index = "0", paramLabel = "PLUGIN_ID", description = "Id of the active plugin to reload.")
    private String pluginId;

    @Option(
            names = {"--jar"},
            required = true,
            paramLabel = "PATH",
            description = "The new plugin JAR, as a path on the server.")
    private Path jar;

    @Option(
            names = {"--server"},
            defaultValue = "http://localhost:9464",
            description = "Base URL of the running observe server. Default: ${DEFAULT-VALUE}")
    private URI server;

    @Option(
            names = {"--api-key"},
            description = "The server API key. Default: the " + ApiKeyStore.ENV_VAR
                    + " environment variable, which keeps the key out of the process list.")
    private String apiKey;

    @Option(
            names = {ObserveServerClient.ALLOW_INSECURE_HTTP},
            description = "Send the key over plain HTTP to another machine anyway (it can be read on the way).")
    private boolean allowInsecureHttp;

    @Mixin
    private CaCertOption caCert;

    @Override
    public Integer call() {
        PrintWriter err = spec.commandLine().getErr();
        if (pluginId.isBlank()) {
            err.println("Error: PLUGIN_ID must not be blank.");
            return ObserveServerClient.EXIT_USAGE;
        }
        Path absoluteJar = jar.toAbsolutePath().normalize();
        String body;
        try {
            body = JSON.writeValueAsString(Map.of(PluginEndpoints.JAR_FIELD, absoluteJar.toString()));
        } catch (JsonProcessingException e) {
            err.println("Error: could not encode the request: " + e.getOriginalMessage());
            return ObserveServerClient.EXIT_FAILED;
        }
        // Login keys are never sent: only the server API key may reload, so they would only be refused.
        ObserveServerClient.Server target =
                ObserveServerClient.Server.of(server, apiKey, null, allowInsecureHttp, caCert);
        return ObserveServerClient.request(
                spec,
                target,
                PluginEndpoints.PATH_PREFIX
                        + ObserveServerClient.encodePathSegment(pluginId)
                        + PluginEndpoints.RELOAD_SUFFIX,
                "POST",
                body,
                (response, out, errOut) -> report(response, out, errOut, absoluteJar));
    }

    private int report(HttpResponse<String> response, PrintWriter out, PrintWriter err, Path absoluteJar) {
        String message = ObserveServerClient.errorMessage(response.body());
        switch (response.statusCode()) {
            case 200 -> {
                out.printf(
                        "Reloaded plugin %s: %s -> %s.%n",
                        pluginId,
                        ObserveServerClient.field(response.body(), "previousVersion")
                                .orElse("?"),
                        ObserveServerClient.field(response.body(), "newVersion").orElse("?"));
                return ObserveServerClient.EXIT_OK;
            }
            case 400 -> err.printf("Error: the server refused %s: %s%n", absoluteJar, message);
            case 403 ->
                err.printf("Error: %s. Pass the server API key with --api-key or %s.%n", message, ApiKeyStore.ENV_VAR);
            case 404 -> err.printf("Error: no active plugin with id %s.%n", pluginId);
            case 409 -> err.printf("Error: plugin %s cannot be reloaded: %s%n", pluginId, message);
            case 422 -> err.printf("Error: %s%n", message);
            case 503 -> err.printf("Error: %s%n", message);
            default -> err.printf("Error: observe server returned HTTP %d: %s%n", response.statusCode(), message);
        }
        return ObserveServerClient.EXIT_FAILED;
    }
}
