package com.nexora.cli;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import javax.net.ssl.SSLContext;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Spec;

/**
 * {@code --cacert} for the commands that call a running observe server, so they can reach one
 * whose certificate comes from a private CA such as {@code nexora tls-gen}'s. Without it the JVM's
 * trusted CAs apply. Host names are checked either way.
 */
final class CaCertOption {

    @Spec(Spec.Target.MIXEE)
    private CommandSpec mixee;

    private SSLContext trust;

    /** Read while the arguments are parsed, so a bad file is a usage error before anything runs. */
    @Option(
            names = {"--cacert"},
            paramLabel = "<ca.pem>",
            description = "PEM certificate authority to trust for an https:// --server, such as the ca.pem"
                    + " `nexora tls-gen` writes. Default: the JVM's trusted authorities.")
    void setCaCert(Path file) {
        try {
            trust = ObserveTls.trusting(file);
        } catch (IllegalArgumentException e) {
            throw new ParameterException(mixee.commandLine(), "Invalid --cacert: " + e.getMessage(), e);
        }
    }

    HttpClient client(Duration connectTimeout) {
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(connectTimeout);
        if (trust != null) {
            builder.sslContext(trust);
        }
        return builder.build();
    }
}
