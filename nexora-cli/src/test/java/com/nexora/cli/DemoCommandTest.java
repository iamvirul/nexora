package com.nexora.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class DemoCommandTest {

    @Test
    void testTimeoutMsZeroThrowsException() {
        DemoCommand demoCommand = new DemoCommand();
        CommandLine cmd = new CommandLine(demoCommand);

        StringWriter sw = new StringWriter();
        cmd.setErr(new PrintWriter(sw));

        int exitCode = cmd.execute("--timeout", "0");
        assertNotEquals(0, exitCode);
        String errOutput = sw.toString();
        assertTrue(
                errOutput.contains("must be greater than zero"),
                "Error message should complain about timeout: " + errOutput);
    }

    @Test
    void testTimeoutMsNegativeThrowsException() {
        DemoCommand demoCommand = new DemoCommand();
        CommandLine cmd = new CommandLine(demoCommand);

        StringWriter sw = new StringWriter();
        cmd.setErr(new PrintWriter(sw));

        int exitCode = cmd.execute("--timeout", "-100");
        assertNotEquals(0, exitCode);
        String errOutput = sw.toString();
        assertTrue(
                errOutput.contains("must be greater than zero"),
                "Error message should complain about timeout: " + errOutput);
    }
}
