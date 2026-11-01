package com.nexora.cli;

import com.nexora.api.auth.ApiKeyStore;
import com.nexora.api.auth.TenantResolver;
import java.io.PrintWriter;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

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
        mixinStandardHelpOptions = true)
public class CancelCommand implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    @Parameters(index = "0", paramLabel = "EXECUTION_ID", description = "ID of the execution to cancel.")
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

    @Option(
            names = {"--credentials-file"},
            defaultValue = CliCredentials.DEFAULT_FILE,
            description = "Where `nexora login` saved user API keys. Default: ${DEFAULT-VALUE}")
    private Path credentialsFile;

    @Option(
            names = {ObserveServerClient.ALLOW_INSECURE_HTTP},
            description = "Send the key over plain HTTP to another machine anyway (it can be read on the way).")
    private boolean allowInsecureHttp;

    @Option(
            names = {"--tenant"},
            description = "Tenant the execution belongs to, sent as " + TenantResolver.HEADER + ". Needed with the"
                    + " server API key on a multi-tenant server; a user's login key already carries its tenant.")
    private String tenant;

    @Mixin
    private CaCertOption caCert;

    @Override
    public Integer call() {
        return ObserveServerClient.call(
                spec,
                ObserveServerClient.Server.of(server, apiKey, credentialsFile, allowInsecureHttp, caCert)
                        .withTenant(tenant),
                executionId,
                "DELETE",
                "",
                this::report);
    }

    private int report(HttpResponse<String> response, PrintWriter out, PrintWriter err) {
        switch (response.statusCode()) {
            case 200 -> {
                out.printf("Cancellation requested for execution %s.%n", executionId);
                return ObserveServerClient.EXIT_OK;
            }
            case 404 -> err.printf("Error: no execution found with id %s.%n", executionId);
            case 409 ->
                err.printf(
                        "Error: execution %s could not be cancelled: %s.%n",
                        executionId, ObserveServerClient.errorMessage(response.body()));
            default ->
                err.printf(
                        "Error: observe server returned HTTP %d: %s%n",
                        response.statusCode(), ObserveServerClient.errorMessage(response.body()));
        }
        return ObserveServerClient.EXIT_FAILED;
    }
}
