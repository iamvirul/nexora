package com.nexora.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** Incomplete, conflicting or unusable TLS options stop `nexora observe` before it binds a port or makes a key. */
class ObserveCommandTlsOptionsTest {

    private static final int EXIT_USAGE = 2;

    @TempDir
    Path home;

    private Path certificate;
    private Path key;

    @BeforeEach
    void setUp() throws Exception {
        DevCertificates.Issued issued = DevCertificates.issue(DevCertificates.DEFAULT_HOSTS, 30, Instant.now());
        certificate =
                Files.writeString(home.resolve("server.pem"), PemFiles.certificatePem(issued.serverCertificate()));
        key = Files.writeString(home.resolve("server-key.pem"), PemFiles.privateKeyPem(issued.serverKey()));
    }

    @Test
    void certificateWithoutKeyIsRejected() {
        Result result = observe("--tls-cert", certificate.toString());

        assertUsageError(result, "--tls-cert and --tls-key must be given together");
    }

    @Test
    void keyWithoutCertificateIsRejected() {
        Result result = observe("--tls-key", key.toString());

        assertUsageError(result, "--tls-cert and --tls-key must be given together");
    }

    @Test
    void pemAndKeystoreTogetherAreRejected() {
        Result result =
                observe("--tls-cert", certificate.toString(), "--tls-key", key.toString(), "--keystore", "server.p12");

        assertUsageError(result, "not both");
    }

    @Test
    void keystoreWithoutAPasswordIsRejected() {
        Result result = observe("--keystore", home.resolve("server.p12").toString());

        assertUsageError(result, ObserveCommand.KEYSTORE_PASSWORD_ENV);
    }

    @Test
    void keystorePasswordWithoutKeystoreIsRejected() {
        Result result = observe("--keystore-password", "secret");

        assertUsageError(result, "--keystore-password needs --keystore");
    }

    @Test
    void unreadableCertificateIsRejectedByName() {
        Path missing = home.resolve("missing.pem");

        Result result = observe("--tls-cert", missing.toString(), "--tls-key", key.toString());

        assertUsageError(result, missing.toString());
    }

    private void assertUsageError(Result result, String expected) {
        assertEquals(EXIT_USAGE, result.exitCode(), result.err());
        assertTrue(result.err().contains(expected), result.err());
        assertFalse(Files.exists(digestFile()), "no API key is generated when TLS options are wrong");
    }

    private Path digestFile() {
        return home.resolve("observe-api-key.sha256");
    }

    private Result observe(String... options) {
        String[] args = new String[options.length + 5];
        args[0] = "observe";
        args[1] = "--api-key-file";
        args[2] = digestFile().toString();
        args[3] = "--users-file";
        args[4] = home.resolve("users").toString();
        System.arraycopy(options, 0, args, 5, options.length);
        StringWriter err = new StringWriter();
        CommandLine cmd = new CommandLine(new NexoraCli(), new EmptyEnvironmentFactory());
        cmd.setErr(new PrintWriter(err));
        int exitCode = cmd.execute(args);
        return new Result(exitCode, err.toString());
    }

    private record Result(int exitCode, String err) {}

    /** No NEXORA_API_KEY or NEXORA_KEYSTORE_PASSWORD from the developer's shell. */
    private static final class EmptyEnvironmentFactory implements CommandLine.IFactory {
        @Override
        public <K> K create(Class<K> type) throws Exception {
            K instance = CommandLine.defaultFactory().create(type);
            if (instance instanceof ObserveCommand observe) {
                observe.environment = name -> null;
            }
            return instance;
        }
    }
}
