package com.nexora.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class TlsGenCommandTest {

    private static final Instant NOW = Instant.parse("2026-10-15T10:00:00Z");

    @TempDir
    Path dir;

    @Test
    void writesACaAndAServerCertificateTheServerCanLoad() throws Exception {
        Path out = dir.resolve("certs");

        Result result = tlsGen("--out", out.toString(), "--host", "nexora.test");

        assertEquals(0, result.exitCode(), result.err());
        X509Certificate ca =
                PemFiles.readCertificates(out.resolve(TlsGenCommand.CA_FILE)).get(0);
        ObserveTls tls =
                ObserveTls.fromPem(out.resolve(TlsGenCommand.CERTIFICATE_FILE), out.resolve(TlsGenCommand.KEY_FILE));
        tls.certificate().verify(ca.getPublicKey());
        assertTrue(tls.certificate().getSubjectAlternativeNames().contains(List.of(2, "nexora.test")));
        assertEquals(
                NOW.minus(Duration.ofHours(1)).plus(Duration.ofDays(365)),
                tls.certificate().getNotAfter().toInstant());
        assertTrue(result.out().contains("--tls-cert"), result.out());
        assertTrue(result.out().contains("curl --cacert"), result.out());
    }

    @Test
    void privateKeyIsOwnerOnly() throws Exception {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        Path out = dir.resolve("certs");

        tlsGen("--out", out.toString());

        assertEquals(
                "rw-------",
                PosixFilePermissions.toString(Files.getPosixFilePermissions(out.resolve(TlsGenCommand.KEY_FILE))));
    }

    @Test
    void existingFilesAreKeptUnlessForced() throws Exception {
        Path out = dir.resolve("certs");
        tlsGen("--out", out.toString());
        String firstCa = Files.readString(out.resolve(TlsGenCommand.CA_FILE));

        Result refused = tlsGen("--out", out.toString());
        assertEquals(2, refused.exitCode());
        assertTrue(refused.err().contains("--force"), refused.err());
        assertEquals(firstCa, Files.readString(out.resolve(TlsGenCommand.CA_FILE)));

        Result forced = tlsGen("--out", out.toString(), "--force");
        assertEquals(0, forced.exitCode(), forced.err());
        assertTrue(!firstCa.equals(Files.readString(out.resolve(TlsGenCommand.CA_FILE))), "a new CA was written");
    }

    @Test
    void invalidHostOrValidityIsAUsageErrorThatWritesNothing() {
        Path out = dir.resolve("certs");

        Result badHost = tlsGen("--out", out.toString(), "--host", "not a host");
        Result badDays = tlsGen("--out", out.toString(), "--days", "0");

        assertEquals(2, badHost.exitCode());
        assertTrue(badHost.err().contains("not a valid host name"), badHost.err());
        assertEquals(2, badDays.exitCode());
        assertTrue(badDays.err().contains("between 1 and"), badDays.err());
        assertTrue(Files.notExists(out.resolve(TlsGenCommand.KEY_FILE)));
    }

    private Result tlsGen(String... args) {
        TlsGenCommand command = new TlsGenCommand();
        command.clock = Clock.fixed(NOW, ZoneOffset.UTC);
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine cmd = new CommandLine(command);
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));
        int exitCode = cmd.execute(args);
        return new Result(exitCode, out.toString(), err.toString());
    }

    private record Result(int exitCode, String out, String err) {}
}
