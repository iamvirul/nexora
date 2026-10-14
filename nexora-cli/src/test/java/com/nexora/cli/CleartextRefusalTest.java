package com.nexora.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** Secrets never go over plain HTTP to another machine unless the caller explicitly opts in. */
class CleartextRefusalTest {

    // `.invalid` never resolves, so an opted-in attempt fails fast instead of waiting on a network timeout.
    private static final String REMOTE_HTTP = "http://nexora-test.invalid:9464";

    @TempDir
    Path dir;

    @Test
    void cancelRefusesToSendAKeyOverRemotePlainHttp() {
        Result result = run(new CancelCommand(), "", "any-id", "--server", REMOTE_HTTP, "--api-key", TestAuth.KEY);

        assertEquals(ObserveServerClient.EXIT_USAGE, result.exitCode());
        assertTrue(result.err().contains("refusing to send the API key over plain HTTP"), result.err());
    }

    @Test
    void optingInSendsAnyway() {
        Result result = run(
                new CancelCommand(),
                "",
                "any-id",
                "--server",
                REMOTE_HTTP,
                "--api-key",
                TestAuth.KEY,
                ObserveServerClient.ALLOW_INSECURE_HTTP);

        assertEquals(ObserveServerClient.EXIT_FAILED, result.exitCode());
        assertTrue(result.err().contains("could not reach"), result.err());
    }

    @Test
    void loginRefusesBeforeAskingForThePassword() {
        LoginCommand login = new LoginCommand();

        Result result = run(
                login,
                "",
                "--server",
                REMOTE_HTTP,
                "-u",
                "alice",
                "--password-stdin",
                "--credentials-file",
                dir.resolve("credentials").toString());

        assertEquals(ObserveServerClient.EXIT_USAGE, result.exitCode());
        assertTrue(result.err().contains("refusing to send the password"), result.err());
        assertFalse(result.err().contains("No password on standard input"), "must refuse before reading the password");
    }

    @Test
    void httpsAndLoopbackAreAllowed() {
        assertTrue(ObserveServerClient.refuseCleartext(java.net.URI.create("https://nexora.example"), "API key", false)
                .isEmpty());
        assertTrue(ObserveServerClient.refuseCleartext(java.net.URI.create("http://localhost:9464"), "API key", false)
                .isEmpty());
        assertTrue(ObserveServerClient.refuseCleartext(java.net.URI.create("http://127.0.0.1:9464"), "API key", false)
                .isEmpty());
    }

    private static Result run(Object command, String stdin, String... args) {
        if (command instanceof LoginCommand login) {
            login.in = new ByteArrayInputStream(stdin.getBytes());
        }
        StringWriter err = new StringWriter();
        CommandLine cmd = new CommandLine(command);
        cmd.setOut(new PrintWriter(new StringWriter()));
        cmd.setErr(new PrintWriter(err));
        return new Result(cmd.execute(args), err.toString());
    }

    private record Result(int exitCode, String err) {}
}
