package com.nexora.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ObserveTlsTest {

    private static final char[] PASSWORD = "keystore-password".toCharArray();

    @TempDir
    Path dir;

    @Test
    void pemCertificateAndKeyLoad() throws Exception {
        DevCertificates.Issued issued = issue(Instant.now());

        ObserveTls tls = ObserveTls.fromPem(write("server.pem", issued), writeKey("key.pem", issued));

        assertEquals(issued.serverCertificate(), tls.certificate());
        assertArrayEquals(ObserveTls.PROTOCOLS, tls.parameters().getProtocols());
    }

    @Test
    void keyFromAnotherCertificateIsRefused() throws Exception {
        DevCertificates.Issued served = issue(Instant.now());
        DevCertificates.Issued other = issue(Instant.now());

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> ObserveTls.fromPem(write("server.pem", served), writeKey("other-key.pem", other)));

        assertTrue(error.getMessage().contains("does not belong"), error.getMessage());
    }

    @Test
    void pkcs12KeystoreLoads() throws Exception {
        DevCertificates.Issued issued = issue(Instant.now());

        ObserveTls tls = ObserveTls.fromPkcs12(keystore(issued, true), PASSWORD);

        assertEquals(issued.serverCertificate(), tls.certificate());
    }

    @Test
    void wrongKeystorePasswordIsReportedAsSuch() throws Exception {
        Path keystore = keystore(issue(Instant.now()), true);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> ObserveTls.fromPkcs12(keystore, "nope".toCharArray()));

        assertTrue(error.getMessage().contains("Wrong password"), error.getMessage());
    }

    @Test
    void keystoreWithoutAPrivateKeyIsRefused() throws Exception {
        Path keystore = keystore(issue(Instant.now()), false);

        IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, () -> ObserveTls.fromPkcs12(keystore, PASSWORD));

        assertTrue(error.getMessage().contains("holds no private key"), error.getMessage());
    }

    @Test
    void missingKeystoreIsReportedByName() {
        Path missing = dir.resolve("missing.p12");

        IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, () -> ObserveTls.fromPkcs12(missing, PASSWORD));

        assertTrue(error.getMessage().contains("does not exist"), error.getMessage());
    }

    @Test
    void validityWarningsCoverExpiredNotYetValidAndExpiringSoon() throws Exception {
        Instant issuedAt = Instant.parse("2026-01-01T00:00:00Z");
        DevCertificates.Issued issued = DevCertificates.issue(DevCertificates.DEFAULT_HOSTS, 30, issuedAt);
        ObserveTls tls = ObserveTls.fromPem(write("server.pem", issued), writeKey("key.pem", issued));

        assertEquals(Optional.empty(), tls.validityWarning(issuedAt.plus(Duration.ofDays(1))));
        Instant expiry = tls.certificate().getNotAfter().toInstant();
        assertTrue(tls.validityWarning(expiry.minus(Duration.ofDays(10)))
                .orElseThrow()
                .contains("expires in 10 day(s)"));
        assertTrue(tls.validityWarning(issuedAt.plus(Duration.ofDays(31)))
                .orElseThrow()
                .contains("expired"));
        assertTrue(tls.validityWarning(issuedAt.minus(Duration.ofDays(1)))
                .orElseThrow()
                .contains("not valid until"));
    }

    @Test
    void trustingAFileWithoutCertificatesFails() throws Exception {
        Path empty = Files.writeString(dir.resolve("empty.pem"), "");

        assertThrows(IllegalArgumentException.class, () -> ObserveTls.trusting(empty));
    }

    private static DevCertificates.Issued issue(Instant now) {
        return DevCertificates.issue(DevCertificates.DEFAULT_HOSTS, 30, now);
    }

    private Path write(String name, DevCertificates.Issued issued) throws Exception {
        return Files.writeString(dir.resolve(name), PemFiles.certificatePem(issued.serverCertificate()));
    }

    private Path writeKey(String name, DevCertificates.Issued issued) throws Exception {
        return Files.writeString(dir.resolve(name), PemFiles.privateKeyPem(issued.serverKey()));
    }

    private Path keystore(DevCertificates.Issued issued, boolean withKey) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        if (withKey) {
            store.setKeyEntry("server", issued.serverKey(), PASSWORD, new Certificate[] {
                issued.serverCertificate(), issued.caCertificate()
            });
        } else {
            store.setCertificateEntry("ca", issued.caCertificate());
        }
        Path file = dir.resolve(withKey ? "server.p12" : "ca-only.p12");
        try (OutputStream out = Files.newOutputStream(file)) {
            store.store(out, PASSWORD);
        }
        return file;
    }
}
