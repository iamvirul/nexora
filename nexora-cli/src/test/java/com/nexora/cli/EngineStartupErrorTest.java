package com.nexora.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class EngineStartupErrorTest {

    private static final String PASSWORD = "s3cret-LEAK-CHECK";

    @TempDir
    Path dir;

    @Test
    void redactsEveryJdbcUrlToItsSubprotocol() {
        String text = "Failed for jdbcUrl=jdbc:postgresql://db.internal:5432/nexora?user=app&password=" + PASSWORD
                + " and jdbc:h2:/data/nexora;AUTO_SERVER=TRUE";

        assertThat(EngineStartupException.redact(text))
                .isEqualTo("Failed for jdbcUrl=jdbc:postgresql and jdbc:h2")
                .doesNotContain(PASSWORD, "db.internal", "app");
    }

    @Test
    void unreachableDatabaseReportsTheReasonButNotTheUrl() {
        Map<String, String> env = Map.of(
                StoreSelection.JDBC_URL_ENV,
                "jdbc:postgresql://127.0.0.1:1/nexora?user=app&password=" + PASSWORD + "&connectTimeout=2");

        assertThatThrownBy(() -> NexoraCli.startEngine(new CliConfig(), env::get))
                .isInstanceOf(EngineStartupException.class)
                .hasNoCause()
                .satisfies(e -> assertThat(e.getMessage())
                        .startsWith("Failed to connect to persistence store: jdbc:postgresql")
                        .doesNotContain(PASSWORD, "127.0.0.1", "user=app"));
    }

    @Test
    void commandPrintsOneErrorLineAndExitsTwo() throws IOException {
        // A pool of size 0 fails when the engine is built, like any store that cannot be opened.
        Path config = Files.writeString(dir.resolve("nexora.json"), """
                {"executionStore": "%s", "persistence": {"pool": {"maximumPoolSize": 0}}}
                """.formatted(
                        dir.resolve("data").toString().replace("\\", "/")));
        StringWriter err = new StringWriter();
        CommandLine cmd = NexoraCli.withStartupErrorHandling(new CommandLine(new NexoraCli()));
        cmd.setErr(new PrintWriter(err, true));
        cmd.setOut(new PrintWriter(new StringWriter(), true));

        int exitCode = cmd.execute("--config", config.toString(), "caps");

        assertThat(exitCode).isEqualTo(2);
        assertThat(err.toString()).startsWith("Error: ").doesNotContain("\tat ", "Exception");
    }
}
