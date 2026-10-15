package com.nexora.cli;

import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Certificates for running the observe server over HTTPS on a development machine.
 *
 * <p>Issues a throwaway certificate authority and a server certificate signed by it, the way
 * {@code mkcert} does. A self-signed server certificate would be simpler, but Firefox refuses a CA
 * certificate used as the server's own, and without the CA flag curl and OpenSSL refuse it as a
 * trust anchor. Trusting {@code ca.pem} instead works everywhere. The CA's private key is never
 * returned, so nothing else can ever be signed with a CA someone has trusted.
 */
final class DevCertificates {

    static final int DEFAULT_VALID_DAYS = 365;
    /** Apple platforms refuse TLS server certificates valid for longer than this. */
    static final int MAX_VALID_DAYS = 825;

    static final List<String> DEFAULT_HOSTS = List.of("localhost", "127.0.0.1", "::1");

    private static final String KEY_ALGORITHM = "EC";
    private static final String CURVE = "secp256r1";
    private static final String SIGNATURE_ALGORITHM = "SHA256withECDSA";
    private static final String OID_ECDSA_WITH_SHA256 = "1.2.840.10045.4.3.2";
    private static final String OID_COMMON_NAME = "2.5.4.3";
    private static final String OID_ORGANIZATION = "2.5.4.10";
    private static final String OID_SUBJECT_KEY_ID = "2.5.29.14";
    private static final String OID_KEY_USAGE = "2.5.29.15";
    private static final String OID_SUBJECT_ALT_NAME = "2.5.29.17";
    private static final String OID_BASIC_CONSTRAINTS = "2.5.29.19";
    private static final String OID_AUTHORITY_KEY_ID = "2.5.29.35";
    private static final String OID_EXTENDED_KEY_USAGE = "2.5.29.37";
    private static final String OID_SERVER_AUTH = "1.3.6.1.5.5.7.3.1";
    private static final String ORGANIZATION = "Nexora development";
    private static final int X509_V3 = 2;
    private static final int SERIAL_BYTES = 16;
    private static final int KEY_ID_BYTES = 20;
    private static final int SAN_DNS_NAME = 2;
    private static final int SAN_IP_ADDRESS = 7;
    private static final int AKI_KEY_IDENTIFIER = 0;
    private static final int EXTENSIONS_TAG = 3;
    private static final int VERSION_TAG = 0;
    // KeyUsage is a named bit list: bit 0 is the high bit of the first byte.
    private static final byte KEY_USAGE_DIGITAL_SIGNATURE = (byte) 0x80;
    private static final int DIGITAL_SIGNATURE_UNUSED_BITS = 7;
    private static final byte KEY_USAGE_CERT_AND_CRL_SIGN = (byte) 0x06;
    private static final int CERT_AND_CRL_SIGN_UNUSED_BITS = 1;
    /** Backdated so a client whose clock runs a little behind does not see a certificate from the future. */
    private static final Duration CLOCK_SKEW = Duration.ofHours(1);

    private static final int MAX_DNS_NAME_LENGTH = 253;
    private static final Pattern DNS_NAME =
            Pattern.compile("^(\\*\\.)?([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)*[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$");
    private static final Pattern NUMERIC_LABEL = Pattern.compile("^\\d+$");
    private static final Pattern IPV4 =
            Pattern.compile("^(25[0-5]|2[0-4]\\d|1?\\d?\\d)(\\.(25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}$");

    private static final SecureRandom RANDOM = new SecureRandom();

    /** What {@code nexora tls-gen} writes out. The CA's private key is deliberately not part of it. */
    record Issued(X509Certificate caCertificate, X509Certificate serverCertificate, PrivateKey serverKey) {}

    private DevCertificates() {}

    /**
     * @param hosts     DNS names and IP literals the server certificate is valid for; the first
     *                  one is also its common name
     * @param validDays how long both certificates stay valid, 1 to {@link #MAX_VALID_DAYS}
     * @throws IllegalArgumentException if a host is not a DNS name or IP literal, or the validity is out of range
     */
    static Issued issue(List<String> hosts, int validDays, Instant now) {
        Objects.requireNonNull(now, "now");
        if (validDays < 1 || validDays > MAX_VALID_DAYS) {
            throw new IllegalArgumentException(
                    "validity must be between 1 and " + MAX_VALID_DAYS + " days, got " + validDays);
        }
        List<SubjectName> names = parseHosts(hosts);
        Instant notBefore = now.minus(CLOCK_SKEW);
        Instant notAfter = now.plus(Duration.ofDays(validDays));
        try {
            KeyPair ca = newKeyPair();
            KeyPair server = newKeyPair();
            byte[] caName = name("Nexora local CA " + LocalDate.ofInstant(now, ZoneOffset.UTC));
            byte[] caKeyId = keyId(ca.getPublic());

            byte[] caExtensions = Der.sequence(
                    extension(OID_BASIC_CONSTRAINTS, true, Der.sequence(Der.bool(true), Der.integer(0))),
                    extension(
                            OID_KEY_USAGE,
                            true,
                            Der.bitString(new byte[] {KEY_USAGE_CERT_AND_CRL_SIGN}, CERT_AND_CRL_SIGN_UNUSED_BITS)),
                    extension(OID_SUBJECT_KEY_ID, false, Der.octetString(caKeyId)));
            X509Certificate caCert =
                    sign(tbs(caName, caName, ca.getPublic(), notBefore, notAfter, caExtensions), ca.getPrivate());

            byte[] serverExtensions = Der.sequence(
                    extension(OID_BASIC_CONSTRAINTS, true, Der.sequence()),
                    extension(
                            OID_KEY_USAGE,
                            true,
                            Der.bitString(new byte[] {KEY_USAGE_DIGITAL_SIGNATURE}, DIGITAL_SIGNATURE_UNUSED_BITS)),
                    extension(OID_EXTENDED_KEY_USAGE, false, Der.sequence(Der.oid(OID_SERVER_AUTH))),
                    extension(OID_SUBJECT_ALT_NAME, false, subjectAltNames(names)),
                    extension(OID_SUBJECT_KEY_ID, false, Der.octetString(keyId(server.getPublic()))),
                    extension(
                            OID_AUTHORITY_KEY_ID,
                            false,
                            Der.sequence(Der.implicitPrimitive(AKI_KEY_IDENTIFIER, caKeyId))));
            byte[] serverName = name(names.get(0).text());
            X509Certificate serverCert = sign(
                    tbs(caName, serverName, server.getPublic(), notBefore, notAfter, serverExtensions),
                    ca.getPrivate());

            // Catches an encoding mistake here rather than as a browser error later.
            caCert.verify(ca.getPublic());
            serverCert.verify(ca.getPublic());
            return new Issued(caCert, serverCert, server.getPrivate());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not issue development certificates: " + e.getMessage(), e);
        }
    }

    /** Each host once, defaults first, in the order given. */
    static List<String> withDefaultHosts(List<String> extraHosts) {
        Set<String> hosts = new LinkedHashSet<>(DEFAULT_HOSTS);
        for (String host : extraHosts) {
            hosts.add(host.strip().toLowerCase(Locale.ROOT));
        }
        return List.copyOf(hosts);
    }

    private record SubjectName(String text, int sanTag, byte[] sanValue) {}

    private static List<SubjectName> parseHosts(List<String> hosts) {
        if (hosts == null || hosts.isEmpty()) {
            throw new IllegalArgumentException("at least one host name is required");
        }
        List<SubjectName> names = new ArrayList<>(hosts.size());
        for (String raw : hosts) {
            names.add(parseHost(raw));
        }
        return names;
    }

    private static SubjectName parseHost(String raw) {
        String host = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
        String unbracketed = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        // Only literals reach InetAddress, so it never does a DNS lookup.
        if (IPV4.matcher(unbracketed).matches() || unbracketed.contains(":")) {
            try {
                return new SubjectName(
                        unbracketed,
                        SAN_IP_ADDRESS,
                        InetAddress.getByName(unbracketed).getAddress());
            } catch (UnknownHostException e) {
                throw new IllegalArgumentException("not a valid IP address: " + raw, e);
            }
        }
        // An all-digit last label means an IPv4 address was meant (RFC 1123 2.1), so 999.1.1.1 is not a name.
        boolean numericTld =
                NUMERIC_LABEL.matcher(host.substring(host.lastIndexOf('.') + 1)).matches();
        if (host.length() > MAX_DNS_NAME_LENGTH
                || numericTld
                || !DNS_NAME.matcher(host).matches()) {
            throw new IllegalArgumentException("not a valid host name or IP address: " + raw);
        }
        return new SubjectName(host, SAN_DNS_NAME, host.getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] subjectAltNames(List<SubjectName> names) {
        byte[][] entries = new byte[names.size()][];
        for (int i = 0; i < names.size(); i++) {
            entries[i] =
                    Der.implicitPrimitive(names.get(i).sanTag(), names.get(i).sanValue());
        }
        return Der.sequence(entries);
    }

    private static byte[] tbs(
            byte[] issuer, byte[] subject, PublicKey key, Instant notBefore, Instant notAfter, byte[] extensions) {
        return Der.sequence(
                Der.explicit(VERSION_TAG, Der.integer(X509_V3)),
                Der.integer(serialNumber()),
                signatureAlgorithm(),
                issuer,
                Der.sequence(Der.time(notBefore), Der.time(notAfter)),
                subject,
                key.getEncoded(),
                Der.explicit(EXTENSIONS_TAG, extensions));
    }

    private static X509Certificate sign(byte[] tbs, PrivateKey issuerKey) throws GeneralSecurityException {
        Signature signer = Signature.getInstance(SIGNATURE_ALGORITHM);
        signer.initSign(issuerKey, RANDOM);
        signer.update(tbs);
        byte[] certificate = Der.sequence(tbs, signatureAlgorithm(), Der.bitString(signer.sign()));
        return (X509Certificate)
                CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(certificate));
    }

    private static byte[] name(String commonName) {
        return Der.sequence(
                Der.set(Der.sequence(Der.oid(OID_ORGANIZATION), Der.utf8String(ORGANIZATION))),
                Der.set(Der.sequence(Der.oid(OID_COMMON_NAME), Der.utf8String(commonName))));
    }

    private static byte[] extension(String oid, boolean critical, byte[] value) {
        // DER leaves out a BOOLEAN that equals its DEFAULT, so "critical" only appears when true.
        return critical
                ? Der.sequence(Der.oid(oid), Der.bool(true), Der.octetString(value))
                : Der.sequence(Der.oid(oid), Der.octetString(value));
    }

    private static byte[] signatureAlgorithm() {
        return Der.sequence(Der.oid(OID_ECDSA_WITH_SHA256));
    }

    /** Positive, non-zero and unpredictable, as RFC 5280 and the CA/Browser Forum ask. */
    private static BigInteger serialNumber() {
        byte[] bytes = new byte[SERIAL_BYTES];
        RANDOM.nextBytes(bytes);
        bytes[0] &= 0x7F;
        bytes[0] |= 0x01;
        return new BigInteger(bytes);
    }

    /** A key identifier only has to be unique, so a truncated SHA-256 of the key serves. */
    private static byte[] keyId(PublicKey key) throws GeneralSecurityException {
        return Arrays.copyOf(MessageDigest.getInstance("SHA-256").digest(key.getEncoded()), KEY_ID_BYTES);
    }

    private static KeyPair newKeyPair() throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance(KEY_ALGORITHM);
        generator.initialize(new ECGenParameterSpec(CURVE), RANDOM);
        return generator.generateKeyPair();
    }
}
