package com.nexora.cli;

import java.io.PrintWriter;
import java.net.URI;
import java.net.http.HttpResponse;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Replays a failed, timed-out, cancelled or compensated execution through a running
 * {@code nexora observe} server ({@code POST /api/executions/{id}/replay}). Going through the server
 * keeps the replay on the engine that owns the capabilities, plugins and store.
 */
@Command(
        name = "replay",
        // description loaded from help/replay.help at startup via HelpLoader
        mixinStandardHelpOptions = true)
public class ReplayCommand implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    @Parameters(index = "0", paramLabel = "EXECUTION_ID", description = "ID of the execution to replay.")
    private String executionId;

    @Option(
            names = {"--server"},
            defaultValue = "http://localhost:9464",
            description = "Base URL of the running observe server. Default: ${DEFAULT-VALUE}")
    private URI server;

    @Option(
            names = {"--api-key"},
            description = "API key for the observe server. Default: the " + ApiKeyStore.ENV_VAR
                    + " environment variable, which keeps the key out of the process list.")
    private String apiKey;

    @Override
    public Integer call() {
        return ObserveServerClient.call(
                spec,
                ObserveServerClient.Server.of(server, apiKey),
                executionId,
                "POST",
                ExecutionEndpoints.REPLAY_SUFFIX,
                this::report);
    }

    private int report(HttpResponse<String> response, PrintWriter out, PrintWriter err) {
        switch (response.statusCode()) {
            case 202 -> {
                String replayId = ObserveServerClient.field(response.body(), "executionId")
                        .orElse("unknown");
                out.printf("Replay started: %s (replay of %s).%n", replayId, executionId);
                return ObserveServerClient.EXIT_OK;
            }
            case 404 -> err.printf("Error: no execution found with id %s.%n", executionId);
            case 409 ->
                err.printf(
                        "Error: execution %s cannot be replayed (state %s). %s.%n",
                        executionId,
                        ObserveServerClient.field(response.body(), "state").orElse("unknown"),
                        ObserveServerClient.errorMessage(response.body()));
            case 503 ->
                err.println("Error: the observe server has no persistence store, so it cannot replay executions.");
            default ->
                err.printf(
                        "Error: observe server returned HTTP %d: %s%n",
                        response.statusCode(), ObserveServerClient.errorMessage(response.body()));
        }
        return ObserveServerClient.EXIT_FAILED;
    }
}
