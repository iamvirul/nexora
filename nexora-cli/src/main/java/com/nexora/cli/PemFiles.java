package com.nexora.cli;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads and writes the PEM files TLS certificates and keys come in: what certbot, a CA, openssl
 * and {@code nexora tls-gen} produce.
 *
 * <p>Private keys may be unencrypted PKCS#8 ({@code BEGIN PRIVATE KEY}, what certbot and openssl 3
 * write) or PKCS#1 RSA ({@code BEGIN RSA PRIVATE KEY}). Encrypted keys and SEC1 EC keys are refused
 * with the openssl command that converts them, rather than half-supported.
 */
final class PemFiles {

    private static final Pattern BLOCK =
            Pattern.compile("-----BEGIN ([A-Z0-9 ]+)-----(.*?)-----END \\1-----", Pattern.DOTALL);
    private static final String PKCS8 = "PRIVATE KEY";
    private static final String PKCS1_RSA = "RSA PRIVATE KEY";
    private static final String PKCS8_ENCRYPTED = "ENCRYPTED PRIVATE KEY";
    private static final String SEC1_EC = "EC PRIVATE KEY";
    private static final String CERTIFICATE = "CERTIFICATE";
    /** Legacy OpenSSL encryption puts headers such as {@code Proc-Type: 4,ENCRYPTED} inside the block. */
    private static final String ENCRYPTED_HEADER = "ENCRYPTED";

    private static final List<String> KEY_ALGORITHMS = List.of("RSA", "EC", "Ed25519", "Ed448");
    private static final String CONVERT_HINT =
            "convert it to an unencrypted PKCS#8 key with: openssl pkcs8 -topk8 -nocrypt -in <key> -out <new-key>";
    // rsaEncryption AlgorithmIdentifier, which PKCS#8 puts in front of a PKCS#1 RSA key.
    private static final String OID_RSA_ENCRYPTION = "1.2.840.113549.1.1.1";
    private static final byte[] DER_NULL = {0x05, 0x00};
    private static final int PEM_LINE_LENGTH = 64;

    private PemFiles() {}

    /**
     * Every certificate in {@code file}, leaf first as a chain file lists them.
     *
     * @throws IllegalArgumentException if the file is missing, unreadable, or holds no certificate
     */
    static List<X509Certificate> readCertificates(Path file) {
        byte[] bytes = read(file, "certificate");
        try {
            List<X509Certificate> certificates = new ArrayList<>();
            for (Certificate certificate :
                    CertificateFactory.getInstance("X.509").generateCertificates(new ByteArrayInputStream(bytes))) {
                certificates.add((X509Certificate) certificate);
            }
            if (certificates.isEmpty()) {
                throw new IllegalArgumentException("No certificate found in " + file);
            }
            return List.copyOf(certificates);
        } catch (CertificateException e) {
            throw new IllegalArgumentException("Could not read certificate file " + file + ": " + e.getMessage(), e);
        }
    }

    /**
     * The one private key in {@code file}.
     *
     * @throws IllegalArgumentException if the file is missing, holds no key, or holds a format this
     *                                  reader does not accept (the message says how to convert it)
     */
    static PrivateKey readPrivateKey(Path file) {
        String text = new String(read(file, "private key"), StandardCharsets.US_ASCII);
        Matcher block = BLOCK.matcher(text);
        while (block.find()) {
            String type = block.group(1);
            String body = block.group(2);
            switch (type) {
                case PKCS8 -> {
                    return pkcs8(decode(body, file), file);
                }
                case PKCS1_RSA -> {
                    if (body.contains(ENCRYPTED_HEADER)) {
                        throw new IllegalArgumentException(
                                "The private key in " + file + " is encrypted; " + CONVERT_HINT);
                    }
                    return pkcs8(wrapPkcs1Rsa(decode(body, file)), file);
                }
                case PKCS8_ENCRYPTED ->
                    throw new IllegalArgumentException("The private key in " + file + " is encrypted; " + CONVERT_HINT);
                case SEC1_EC ->
                    throw new IllegalArgumentException(
                            "The private key in " + file + " is a SEC1 EC key; " + CONVERT_HINT);
                default -> {
                    // A certificate in the same file is fine; keep looking for the key.
                }
            }
        }
        throw new IllegalArgumentException(
                "No private key found in " + file + " (expected -----BEGIN PRIVATE KEY-----)");
    }

    static String certificatePem(X509Certificate certificate) {
        try {
            return pem(CERTIFICATE, certificate.getEncoded());
        } catch (CertificateException e) {
            throw new IllegalStateException("Could not encode certificate", e);
        }
    }

    /** Unencrypted PKCS#8: the file has to be protected by its permissions instead. */
    static String privateKeyPem(PrivateKey key) {
        return pem(PKCS8, key.getEncoded());
    }

    private static PrivateKey pkcs8(byte[] der, Path file) {
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);
        for (String algorithm : KEY_ALGORITHMS) {
            try {
                return KeyFactory.getInstance(algorithm).generatePrivate(spec);
            } catch (InvalidKeySpecException e) {
                // Not this algorithm; try the next one.
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("Key algorithm " + algorithm + " is unavailable", e);
            }
        }
        throw new IllegalArgumentException("Could not read the private key in " + file + ": unsupported key type");
    }

    /** PKCS#8 for RSA is {@code SEQUENCE { 0, rsaEncryption, OCTET STRING { PKCS#1 key } }}. */
    private static byte[] wrapPkcs1Rsa(byte[] pkcs1) {
        return Der.sequence(
                Der.integer(0), Der.sequence(Der.oid(OID_RSA_ENCRYPTION), DER_NULL), Der.octetString(pkcs1));
    }

    private static byte[] decode(String body, Path file) {
        try {
            return Base64.getMimeDecoder().decode(body.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("The PEM data in " + file + " is not valid base64", e);
        }
    }

    private static String pem(String type, byte[] der) {
        String base64 = Base64.getMimeEncoder(PEM_LINE_LENGTH, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(der);
        return "-----BEGIN " + type + "-----\n" + base64 + "\n-----END " + type + "-----\n";
    }

    private static byte[] read(Path file, String what) {
        try {
            return Files.readAllBytes(file);
        } catch (NoSuchFileException e) {
            throw new IllegalArgumentException("The " + what + " file " + file + " does not exist", e);
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "Could not read the " + what + " file " + file + ": " + e.getMessage(), e);
        }
    }
}
