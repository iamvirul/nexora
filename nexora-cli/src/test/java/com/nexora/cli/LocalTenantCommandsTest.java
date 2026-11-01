package com.nexora.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nexora.persistence.DeadLetterRecord;
import com.nexora.persistence.DeadLetterReviewState;
import com.nexora.persistence.MissedFirePolicy;
import com.nexora.persistence.ScheduleRecord;
import com.nexora.persistence.jdbc.JdbcExecutionStore;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** {@code nexora dlq} and {@code nexora schedule} read the store directly; --tenant limits them to one tenant. */
class LocalTenantCommandsTest {

    private static final Instant T0 = Instant.parse("2026-10-01T10:00:00Z");

    @TempDir
    Path dir;

    private Path config;
    private JdbcExecutionStore store;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private PrintStream originalOut;
    private PrintStream originalErr;

    @BeforeEach
    void setUp() throws Exception {
        String dbPath = dir.resolve("nexora-data").toString();
        config = dir.resolve("nexora.json");
        Files.writeString(config, "{\"executionStore\": \"" + dbPath.replace("\\", "\\\\") + "\"}");
        store = JdbcExecutionStore.h2AutoServer(dbPath);
        store.createDeadLetter(DeadLetterRecord.pending("dl-acme", "e-1", "ship", Map.of(), "X", "x", T0, "acme"));
        store.createDeadLetter(DeadLetterRecord.pending("dl-globex", "e-2", "ship", Map.of(), "X", "x", T0, "globex"));
        store.createSchedule(schedule("s-acme", "acme"));
        store.createSchedule(schedule("s-globex", "globex"));
        originalOut = System.out;
        originalErr = System.err;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void tearDown() {
        System.setOut(originalOut);
        System.setErr(originalErr);
        store.close();
    }

    @Test
    void dlqListWithTenantShowsOnlyThatTenant() {
        assertEquals(0, run("dlq", "list", "--tenant", "acme"));

        assertTrue(out().contains("dl-acme"), out());
        assertFalse(out().contains("dl-globex"), out());
    }

    @Test
    void dlqListWithoutTenantShowsEveryTenant() {
        assertEquals(0, run("dlq", "list"));

        assertTrue(out().contains("dl-acme") && out().contains("dl-globex"), out());
    }

    @Test
    void dlqResolveOfAnotherTenantsDeadLetterIsNotFound() {
        assertEquals(1, run("dlq", "resolve", "dl-globex", "--tenant", "acme"));

        assertTrue(err().contains("not found"), err());
        assertEquals(
                DeadLetterReviewState.PENDING,
                store.findDeadLetterById("dl-globex").orElseThrow().reviewState());
    }

    @Test
    void scheduleListAndRemoveStayWithinTheTenant() {
        assertEquals(0, run("schedule", "list", "--tenant", "globex"));
        String listed = out();
        int removeOther = run("schedule", "remove", "s-acme", "--tenant", "globex");

        assertTrue(listed.contains("s-globex"), listed);
        assertFalse(listed.contains("s-acme"), listed);
        assertEquals(1, removeOther);
        assertTrue(store.findScheduleById("s-acme").orElseThrow().active());
    }

    @Test
    void scheduleAddRunsForTheTenant() {
        assertEquals(0, run("schedule", "add", "--goal", "nightly", "--cron", "0 0 * * *", "--tenant", "acme"));

        assertTrue(out().contains("Tenant:    acme"), out());
        assertEquals(
                2,
                store.findAllSchedules().stream()
                        .filter(s -> "acme".equals(s.tenantId()))
                        .count());
    }

    @Test
    void invalidTenantIsRejected() {
        assertEquals(1, run("dlq", "list", "--tenant", "Not Valid"));
        assertEquals(1, run("schedule", "list", "--tenant", "Not Valid"));
    }

    private int run(String... args) {
        String[] full = new String[args.length + 2];
        full[0] = "-c";
        full[1] = config.toString();
        System.arraycopy(args, 0, full, 2, args.length);
        return new CommandLine(new NexoraCli()).execute(full);
    }

    private String out() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String err() {
        return err.toString(StandardCharsets.UTF_8);
    }

    private static ScheduleRecord schedule(String id, String tenantId) {
        return new ScheduleRecord(
                id,
                "0 0 * * *",
                "nightly",
                Map.of(),
                MissedFirePolicy.SKIP,
                T0,
                null,
                T0.plusSeconds(86_400),
                null,
                true,
                tenantId);
    }
}
