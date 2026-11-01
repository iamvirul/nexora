package com.nexora.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nexora.api.auth.ApiKeyStore;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** Invalid key options stop `nexora observe` before it builds an engine or binds a port. */
class ObserveCommandAuthOptionsTest {

    private static final int EXIT_USAGE = 2;

    @TempDir
    Path home;

    @Test
    void rotationCannotBeCombinedWithASuppliedKey() {
        Result result = observe("--rotate-api-key", "--api-key", TestAuth.KEY);

        assertEquals(EXIT_USAGE, result.exitCode());
        assertTrue(result.err().contains("--rotate-api-key cannot be combined"), result.err());
        assertFalse(Files.exists(digestFile()));
    }

    @Test
    void shortKeyIsRejected() {
        Result result = observe("--api-key", "too-short");

        assertEquals(EXIT_USAGE, result.exitCode());
        assertTrue(result.err().contains("at least " + ApiKeyStore.MIN_SUPPLIED_KEY_LENGTH), result.err());
    }

    @Test
    void corruptDigestFileIsReported() throws Exception {
        Files.writeString(digestFile(), "garbage");

        Result result = observe();

        assertEquals(EXIT_USAGE, result.exitCode());
        assertTrue(result.err().contains("--rotate-api-key"), result.err());
    }

    @Test
    void invalidRateLimitConfigStopsStartupBeforeAKeyIsGenerated() throws Exception {
        Path config = home.resolve("nexora.json");
        Files.writeString(config, "{\"observe\": {\"rateLimit\": {\"executeRpm\": 0}}}");

        Result result = run("-c", config.toString(), "observe");

        assertEquals(EXIT_USAGE, result.exitCode());
        assertTrue(result.err().contains("observe.rateLimit"), result.err());
        // Had the key come first, its digest would be saved here and the printed key never shown.
        assertFalse(Files.exists(digestFile()));
    }

    private Path digestFile() {
        return home.resolve("observe-api-key.sha256");
    }

    private Result observe(String... options) {
        String[] args = new String[options.length + 1];
        args[0] = "observe";
        System.arraycopy(options, 0, args, 1, options.length);
        return run(args);
    }

    /** {@code args} must contain the "observe" subcommand; root options such as -c may come before it. */
    private Result run(String... args) {
        // Both credential files live in the temp dir, never the developer's real ~/.nexora.
        String[] full = new String[args.length + 4];
        System.arraycopy(args, 0, full, 0, args.length);
        full[args.length] = "--api-key-file";
        full[args.length + 1] = digestFile().toString();
        full[args.length + 2] = "--users-file";
        full[args.length + 3] = home.resolve("users").toString();
        StringWriter err = new StringWriter();
        CommandLine cmd = new CommandLine(new NexoraCli(), new NoEnvironmentFactory());
        cmd.setErr(new PrintWriter(err));
        int exitCode = cmd.execute(full);
        return new Result(exitCode, err.toString());
    }

    private record Result(int exitCode, String err) {}

    /** Hands ObserveCommand an empty environment, so NEXORA_API_KEY in the developer's shell cannot change the outcome. */
    private static final class NoEnvironmentFactory implements CommandLine.IFactory {
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
