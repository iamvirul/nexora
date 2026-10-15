package com.nexora.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PemFilesTest {

    // An RSA PKCS#8 key is a 26-byte header (version, rsaEncryption, OCTET STRING header) then the PKCS#1 key.
    private static final int PKCS8_RSA_HEADER = 26;
    private static final int OCTET_STRING_AT = 22;

    @TempDir
    Path dir;

    @Test
    void privateKeyRoundTripsThroughPkcs8Pem() throws Exception {
        PrivateKey key = DevCertificates.issue(DevCertificates.DEFAULT_HOSTS, 30, Instant.now())
                .serverKey();
        Path file = write("key.pem", PemFiles.privateKeyPem(key));

        PrivateKey read = PemFiles.readPrivateKey(file);

        assertEquals("EC", read.getAlgorithm());
        assertArrayEquals(key.getEncoded(), read.getEncoded());
        assertTrue(Files.readString(file).startsWith("-----BEGIN PRIVATE KEY-----\n"));
    }

    @Test
    void rsaKeyIsReadFromPkcs8AndFromLegacyPkcs1() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        byte[] pkcs8 = generator.generateKeyPair().getPrivate().getEncoded();
        assertEquals(0x04, pkcs8[OCTET_STRING_AT], "PKCS#8 layout assumed by this test");
        byte[] pkcs1 = Arrays.copyOfRange(pkcs8, PKCS8_RSA_HEADER, pkcs8.length);

        PrivateKey fromPkcs8 = PemFiles.readPrivateKey(write("rsa8.pem", pem("PRIVATE KEY", pkcs8)));
        PrivateKey fromPkcs1 = PemFiles.readPrivateKey(write("rsa1.pem", pem("RSA PRIVATE KEY", pkcs1)));

        assertEquals("RSA", fromPkcs1.getAlgorithm());
        assertArrayEquals(fromPkcs8.getEncoded(), fromPkcs1.getEncoded());
    }

    @Test
    void keyIsFoundAfterACertificateInTheSameFile() throws Exception {
        DevCertificates.Issued issued = DevCertificates.issue(DevCertificates.DEFAULT_HOSTS, 30, Instant.now());
        Path file = write(
                "combined.pem",
                PemFiles.certificatePem(issued.serverCertificate()) + PemFiles.privateKeyPem(issued.serverKey()));

        assertArrayEquals(
                issued.serverKey().getEncoded(), PemFiles.readPrivateKey(file).getEncoded());
    }

    @Test
    void certificateChainIsReadInFileOrder() throws Exception {
        DevCertificates.Issued issued = DevCertificates.issue(DevCertificates.DEFAULT_HOSTS, 30, Instant.now());
        Path file = write(
                "fullchain.pem",
                PemFiles.certificatePem(issued.serverCertificate()) + PemFiles.certificatePem(issued.caCertificate()));

        List<X509Certificate> chain = PemFiles.readCertificates(file);

        assertEquals(List.of(issued.serverCertificate(), issued.caCertificate()), chain);
    }

    @Test
    void encryptedKeysAreRefusedWithTheConversionCommand() throws Exception {
        Path pkcs8 = write("enc8.pem", pem("ENCRYPTED PRIVATE KEY", new byte[] {1, 2, 3}));
        Path legacy = write(
                "enc1.pem",
                "-----BEGIN RSA PRIVATE KEY-----\nProc-Type: 4,ENCRYPTED\nDEK-Info: AES-256-CBC,00\n\nAAAA\n"
                        + "-----END RSA PRIVATE KEY-----\n");

        for (Path file : List.of(pkcs8, legacy)) {
            IllegalArgumentException error =
                    assertThrows(IllegalArgumentException.class, () -> PemFiles.readPrivateKey(file));
            assertTrue(error.getMessage().contains("encrypted"), error.getMessage());
            assertTrue(error.getMessage().contains("openssl pkcs8 -topk8 -nocrypt"), error.getMessage());
        }
    }

    @Test
    void sec1EcKeyIsRefusedWithTheConversionCommand() throws Exception {
        Path file = write("ec.pem", pem("EC PRIVATE KEY", new byte[] {1, 2, 3}));

        IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, () -> PemFiles.readPrivateKey(file));

        assertTrue(error.getMessage().contains("SEC1"), error.getMessage());
        assertTrue(error.getMessage().contains("openssl pkcs8 -topk8 -nocrypt"), error.getMessage());
    }

    @Test
    void fileWithoutAKeyIsRefused() throws Exception {
        DevCertificates.Issued issued = DevCertificates.issue(DevCertificates.DEFAULT_HOSTS, 30, Instant.now());
        Path file = write("cert-only.pem", PemFiles.certificatePem(issued.serverCertificate()));

        IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, () -> PemFiles.readPrivateKey(file));

        assertTrue(error.getMessage().contains("No private key found"), error.getMessage());
    }

    @Test
    void garbageInsteadOfAKeyIsRefused() throws Exception {
        Path file = write("garbage.pem", pem("PRIVATE KEY", new byte[] {1, 2, 3, 4}));

        IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, () -> PemFiles.readPrivateKey(file));

        assertTrue(error.getMessage().contains("unsupported key type"), error.getMessage());
    }

    @Test
    void missingAndEmptyFilesAreReportedByName() throws Exception {
        Path missing = dir.resolve("missing.pem");
        Path empty = write("empty.pem", "");

        IllegalArgumentException noFile =
                assertThrows(IllegalArgumentException.class, () -> PemFiles.readCertificates(missing));
        IllegalArgumentException noCertificate =
                assertThrows(IllegalArgumentException.class, () -> PemFiles.readCertificates(empty));

        assertTrue(noFile.getMessage().contains("does not exist"), noFile.getMessage());
        assertTrue(noCertificate.getMessage().contains(empty.toString()), noCertificate.getMessage());
    }

    private Path write(String name, String content) throws Exception {
        return Files.writeString(dir.resolve(name), content);
    }

    private static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n" + Base64.getMimeEncoder().encodeToString(der) + "\n-----END " + type
                + "-----\n";
    }
}
