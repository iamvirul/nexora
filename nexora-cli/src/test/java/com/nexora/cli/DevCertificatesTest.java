package com.nexora.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import org.junit.jupiter.api.Test;

class DevCertificatesTest {

    private static final Instant NOW = Instant.parse("2026-10-15T10:00:00Z");
    private static final String SERVER_AUTH = "1.3.6.1.5.5.7.3.1";
    private static final int SAN_DNS = 2;
    private static final int SAN_IP = 7;

    @Test
    void serverCertificateNamesEveryHostAsDnsOrIpEntries() throws Exception {
        DevCertificates.Issued issued = DevCertificates.issue(
                DevCertificates.withDefaultHosts(List.of("nexora.test", "10.0.0.5", "[fd00::1]")), 365, NOW);

        Set<String> names = issued.serverCertificate().getSubjectAlternativeNames().stream()
                .map(entry -> entry.get(0) + ":" + entry.get(1))
                .collect(Collectors.toSet());

        assertEquals(
                Set.of(
                        SAN_DNS + ":localhost",
                        SAN_IP + ":127.0.0.1",
                        SAN_IP + ":0:0:0:0:0:0:0:1",
                        SAN_DNS + ":nexora.test",
                        SAN_IP + ":10.0.0.5",
                        SAN_IP + ":fd00:0:0:0:0:0:0:1"),
                names);
        assertTrue(
                issued.serverCertificate().getSubjectX500Principal().getName().contains("CN=localhost"));
    }

    @Test
    void serverCertificateIsASignedEndEntityForServerAuth() throws Exception {
        DevCertificates.Issued issued = DevCertificates.issue(DevCertificates.DEFAULT_HOSTS, 365, NOW);
        X509Certificate server = issued.serverCertificate();
        X509Certificate ca = issued.caCertificate();

        server.verify(ca.getPublicKey());
        assertEquals(-1, server.getBasicConstraints(), "not a CA");
        assertTrue(server.getKeyUsage()[0], "digitalSignature");
        assertEquals(List.of(SERVER_AUTH), server.getExtendedKeyUsage());
        assertEquals(ca.getSubjectX500Principal(), server.getIssuerX500Principal());
        assertTrue(server.getCriticalExtensionOIDs().containsAll(Set.of("2.5.29.15", "2.5.29.19")));
    }

    @Test
    void caCertificateMaySignOnlyEndEntities() throws Exception {
        X509Certificate ca =
                DevCertificates.issue(DevCertificates.DEFAULT_HOSTS, 365, NOW).caCertificate();

        ca.verify(ca.getPublicKey());
        assertEquals(0, ca.getBasicConstraints(), "CA with path length 0");
        boolean[] usage = ca.getKeyUsage();
        assertTrue(usage[5] && usage[6], "keyCertSign and cRLSign");
        assertTrue(!usage[0], "no digitalSignature");
    }

    @Test
    void theJdkTrustsTheServerCertificateThroughTheCa() throws Exception {
        DevCertificates.Issued issued = DevCertificates.issue(DevCertificates.DEFAULT_HOSTS, 365, Instant.now());
        KeyStore anchors = KeyStore.getInstance("PKCS12");
        anchors.load(null, null);
        anchors.setCertificateEntry("ca", issued.caCertificate());
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(anchors);
        X509TrustManager trust = (X509TrustManager) factory.getTrustManagers()[0];

        // Throws if PKIX validation (signature, validity, key usage, EKU) rejects the chain.
        trust.checkServerTrusted(new X509Certificate[] {issued.serverCertificate()}, "ECDHE_ECDSA");
    }

    @Test
    void validityStartsSlightlyInThePastAndLastsTheRequestedDays() {
        X509Certificate server =
                DevCertificates.issue(DevCertificates.DEFAULT_HOSTS, 30, NOW).serverCertificate();

        assertEquals(NOW.minus(Duration.ofHours(1)), server.getNotBefore().toInstant());
        assertEquals(NOW.plus(Duration.ofDays(30)), server.getNotAfter().toInstant());
    }

    @Test
    void eachRunHasItsOwnCaKeyAndSerialNumbers() {
        DevCertificates.Issued first = DevCertificates.issue(DevCertificates.DEFAULT_HOSTS, 365, NOW);
        DevCertificates.Issued second = DevCertificates.issue(DevCertificates.DEFAULT_HOSTS, 365, NOW);

        assertNotEquals(
                first.caCertificate().getPublicKey(), second.caCertificate().getPublicKey());
        assertNotEquals(
                first.serverCertificate().getSerialNumber(),
                second.serverCertificate().getSerialNumber());
        assertTrue(first.serverCertificate().getSerialNumber().signum() > 0);
    }

    @Test
    void defaultHostsComeFirstAndDuplicatesAreDropped() {
        assertEquals(
                List.of("localhost", "127.0.0.1", "::1", "nexora.test"),
                DevCertificates.withDefaultHosts(List.of("Nexora.test", "localhost", "nexora.test")));
    }

    @Test
    void invalidHostNamesAreRejected() {
        for (String bad :
                List.of("bad host", "a..b", "-x.example", "x-.example", "", "999.1.1.1", "10.0.0", "exa_mple.com")) {
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class,
                    () -> DevCertificates.issue(List.of(bad), 365, NOW),
                    () -> "accepted " + bad);
            assertTrue(error.getMessage().contains("not a valid"), error.getMessage());
        }
    }

    @Test
    void validityOutsideOneToMaxDaysIsRejected() {
        assertThrows(
                IllegalArgumentException.class, () -> DevCertificates.issue(DevCertificates.DEFAULT_HOSTS, 0, NOW));
        assertThrows(
                IllegalArgumentException.class,
                () -> DevCertificates.issue(DevCertificates.DEFAULT_HOSTS, DevCertificates.MAX_VALID_DAYS + 1, NOW));
    }
}
