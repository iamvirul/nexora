package com.nexora.cli;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.UnrecoverableKeyException;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManagerFactory;

/**
 * The certificate and key the observe server presents over HTTPS and WSS, loaded from PEM files
 * or a PKCS12 keystore and checked before any port is bound.
 */
public final class ObserveTls {

    /** TLS 1.0 and 1.1 are deprecated (RFC 8996); nothing that can reach this server needs them. */
    static final String[] PROTOCOLS = {"TLSv1.3", "TLSv1.2"};

    static final Duration EXPIRY_WARNING = Duration.ofDays(14);

    private static final String KEY_ALIAS = "observe";
    private static final String PKCS12 = "PKCS12";
    private static final byte[] PROBE = "nexora key pair check".getBytes(StandardCharsets.US_ASCII);

    private final SSLContext context;
    private final X509Certificate certificate;

    private ObserveTls(SSLContext context, X509Certificate certificate) {
        this.context = context;
        this.certificate = certificate;
    }

    /**
     * @param certificateFile the server certificate, optionally followed by its chain (certbot's {@code fullchain.pem})
     * @param keyFile         its private key, unencrypted (certbot's {@code privkey.pem})
     * @throws IllegalArgumentException if either file cannot be read or the key does not belong to the certificate
     */
    public static ObserveTls fromPem(Path certificateFile, Path keyFile) {
        List<X509Certificate> chain = PemFiles.readCertificates(certificateFile);
        PrivateKey key = PemFiles.readPrivateKey(keyFile);
        if (!keyMatches(key, chain.get(0))) {
            throw new IllegalArgumentException("The private key in " + keyFile
                    + " does not belong to the first certificate in " + certificateFile
                    + "; the certificate file must start with the server's own certificate");
        }
        char[] password = randomPassword();
        try {
            KeyStore store = KeyStore.getInstance(PKCS12);
            store.load(null, null);
            store.setKeyEntry(KEY_ALIAS, key, password, chain.toArray(new Certificate[0]));
            return new ObserveTls(sslContext(store, password), chain.get(0));
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalArgumentException("Could not load the TLS certificate: " + e.getMessage(), e);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    /**
     * @param keystoreFile a PKCS12 file holding the server's private key and certificate chain
     * @param password     the keystore password, also used for the key entry as PKCS12 tools do
     * @throws IllegalArgumentException if the file cannot be opened with this password or holds no key
     */
    public static ObserveTls fromPkcs12(Path keystoreFile, char[] password) {
        Objects.requireNonNull(password, "password");
        KeyStore store;
        try (InputStream in = Files.newInputStream(keystoreFile)) {
            store = KeyStore.getInstance(PKCS12);
            store.load(in, password);
        } catch (NoSuchFileException e) {
            throw new IllegalArgumentException("The keystore file " + keystoreFile + " does not exist", e);
        } catch (IOException e) {
            // PKCS12 reports a wrong password as an IOException caused by UnrecoverableKeyException.
            if (e.getCause() instanceof UnrecoverableKeyException) {
                throw new IllegalArgumentException("Wrong password for the keystore " + keystoreFile, e);
            }
            throw new IllegalArgumentException(
                    "Could not read the keystore " + keystoreFile + " as PKCS12: " + e.getMessage(), e);
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException(
                    "Could not read the keystore " + keystoreFile + ": " + e.getMessage(), e);
        }
        try {
            X509Certificate leaf = firstKeyCertificate(store)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "The keystore " + keystoreFile + " holds no private key with a certificate"));
            return new ObserveTls(sslContext(store, password), leaf);
        } catch (UnrecoverableKeyException e) {
            throw new IllegalArgumentException(
                    "The key in " + keystoreFile
                            + " has a different password from the keystore; give both the same password",
                    e);
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Could not use the keystore " + keystoreFile + ": " + e.getMessage(), e);
        }
    }

    SSLContext context() {
        return context;
    }

    /** The certificate presented to clients. */
    public X509Certificate certificate() {
        return certificate;
    }

    /** Protocols pinned to {@link #PROTOCOLS}; cipher suites stay the JDK's defaults, which track current advice. */
    SSLParameters parameters() {
        SSLParameters parameters = context.getDefaultSSLParameters();
        parameters.setProtocols(PROTOCOLS.clone());
        return parameters;
    }

    /** A warning for a certificate that has expired, is not valid yet, or expires soon; empty when it is fine. */
    Optional<String> validityWarning(Instant now) {
        Instant notBefore = certificate.getNotBefore().toInstant();
        Instant notAfter = certificate.getNotAfter().toInstant();
        String subject = certificate.getSubjectX500Principal().getName();
        if (now.isAfter(notAfter)) {
            return Optional.of("The TLS certificate for " + subject + " expired at " + notAfter
                    + "; clients will refuse to connect until it is renewed");
        }
        if (now.isBefore(notBefore)) {
            return Optional.of("The TLS certificate for " + subject + " is not valid until " + notBefore
                    + "; check this machine's clock");
        }
        if (now.plus(EXPIRY_WARNING).isAfter(notAfter)) {
            long days = Duration.between(now, notAfter).toDays();
            return Optional.of("The TLS certificate for " + subject + " expires in " + days + " day(s), at " + notAfter
                    + "; renew it and restart the server");
        }
        return Optional.empty();
    }

    /**
     * A client context that trusts only the certificates in {@code caFile}, for reaching a server
     * whose certificate is signed by a private CA such as the one {@code nexora tls-gen} makes.
     * Host name checks stay on.
     */
    static SSLContext trusting(Path caFile) {
        List<X509Certificate> anchors = PemFiles.readCertificates(caFile);
        try {
            KeyStore store = KeyStore.getInstance(PKCS12);
            store.load(null, null);
            for (int i = 0; i < anchors.size(); i++) {
                store.setCertificateEntry("ca-" + i, anchors.get(i));
            }
            TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trust.init(store);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trust.getTrustManagers(), new SecureRandom());
            return context;
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalArgumentException(
                    "Could not trust the certificates in " + caFile + ": " + e.getMessage(), e);
        }
    }

    private static SSLContext sslContext(KeyStore store, char[] password) throws GeneralSecurityException {
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, password);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keys.getKeyManagers(), null, new SecureRandom());
        return context;
    }

    private static Optional<X509Certificate> firstKeyCertificate(KeyStore store) throws GeneralSecurityException {
        for (String alias : Collections.list(store.aliases())) {
            if (store.isKeyEntry(alias) && store.getCertificate(alias) instanceof X509Certificate leaf) {
                return Optional.of(leaf);
            }
        }
        return Optional.empty();
    }

    /** Signs with the key and verifies with the certificate: works for every key type, unlike comparing fields. */
    private static boolean keyMatches(PrivateKey key, X509Certificate certificate) {
        String algorithm = signatureAlgorithmFor(key.getAlgorithm());
        try {
            Signature signer = Signature.getInstance(algorithm);
            signer.initSign(key);
            signer.update(PROBE);
            byte[] signature = signer.sign();
            Signature verifier = Signature.getInstance(algorithm);
            verifier.initVerify(certificate.getPublicKey());
            verifier.update(PROBE);
            return verifier.verify(signature);
        } catch (GeneralSecurityException e) {
            // A key of another type than the certificate's lands here too.
            return false;
        }
    }

    private static String signatureAlgorithmFor(String keyAlgorithm) {
        return switch (keyAlgorithm.toUpperCase(Locale.ROOT)) {
            case "EC" -> "SHA256withECDSA";
            case "ED25519", "ED448", "EDDSA" -> keyAlgorithm;
            default -> "SHA256withRSA";
        };
    }

    /** The in-memory keystore needs a password; it never leaves this process. */
    private static char[] randomPassword() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes).toCharArray();
    }
}
