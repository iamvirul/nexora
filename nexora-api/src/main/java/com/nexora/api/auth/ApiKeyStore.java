package com.nexora.api.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Resolves the observe server's API key and keeps only its SHA-256 digest on disk.
 *
 * <p>Precedence: {@code --api-key}, then {@value #ENV_VAR}, then the digest file. With none of
 * those, a key is generated, its digest saved, and the plain key returned once so the operator
 * can copy it. The plain key is never written anywhere.
 *
 * <p>SHA-256 rather than a password hash such as bcrypt: a generated key carries 256 bits of
 * entropy, so a slow hash adds nothing, and supplied keys must be at least
 * {@value #MIN_SUPPLIED_KEY_LENGTH} characters for the same reason.
 */
public final class ApiKeyStore {

    public static final String ENV_VAR = "NEXORA_API_KEY";
    public static final String KEY_PREFIX = "nxk_";
    public static final int MIN_SUPPLIED_KEY_LENGTH = 32;

    private static final int KEY_BYTES = 32;
    private static final String DIGEST_ALGORITHM = "SHA-256";
    private static final Pattern DIGEST_HEX = Pattern.compile("[0-9a-fA-F]{64}");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();

    /** Where the key in use came from. */
    public enum Source {
        FLAG,
        ENVIRONMENT,
        FILE,
        GENERATED,
        ROTATED
    }

    /**
     * The digest to verify requests against, plus the plain key when this call created it. A key
     * read from the digest file cannot be shown again; only its digest is known.
     */
    public record Resolution(byte[] digest, Source source, Optional<String> newKey) {
        public Resolution {
            digest = digest.clone();
        }

        @Override
        public byte[] digest() {
            return digest.clone();
        }
    }

    private ApiKeyStore() {}

    /**
     * @param flagKey   value of {@code --api-key}, or null
     * @param envKey    value of {@value #ENV_VAR}, or null
     * @param digestFile where the digest of a generated key is kept
     * @throws IllegalArgumentException if a supplied key is too short
     * @throws IllegalStateException if the digest file is unreadable, corrupt, or cannot be written
     */
    public static Resolution resolve(String flagKey, String envKey, Path digestFile) {
        Objects.requireNonNull(digestFile, "digestFile");
        if (flagKey != null) {
            return new Resolution(digest(requireStrong(flagKey, "--api-key")), Source.FLAG, Optional.empty());
        }
        if (envKey != null && !envKey.isBlank()) {
            return new Resolution(digest(requireStrong(envKey, ENV_VAR)), Source.ENVIRONMENT, Optional.empty());
        }
        if (Files.exists(digestFile)) {
            return new Resolution(readDigest(digestFile), Source.FILE, Optional.empty());
        }
        return generate(digestFile, Source.GENERATED);
    }

    /** Replaces the stored digest with a new key's. The old key stops working on the next start. */
    public static Resolution rotate(Path digestFile) {
        Objects.requireNonNull(digestFile, "digestFile");
        return generate(digestFile, Source.ROTATED);
    }

    public static byte[] digest(String key) {
        try {
            return MessageDigest.getInstance(DIGEST_ALGORITHM).digest(key.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            // Every Java platform is required to provide SHA-256.
            throw new IllegalStateException(DIGEST_ALGORITHM + " is unavailable", e);
        }
    }

    /**
     * {@value #ENV_VAR} when set, otherwise a fresh key that lives only for this process and is
     * returned so it can be shown. For samples and demos, which should not leave files behind.
     *
     * @throws IllegalArgumentException if the environment key is too short
     */
    public static Resolution forThisRun(String envKey) {
        if (envKey != null && !envKey.isBlank()) {
            return new Resolution(digest(requireStrong(envKey, ENV_VAR)), Source.ENVIRONMENT, Optional.empty());
        }
        String key = newKey();
        return new Resolution(digest(key), Source.GENERATED, Optional.of(key));
    }

    private static Resolution generate(Path digestFile, Source source) {
        String key = newKey();
        byte[] digest = digest(key);
        writeDigest(digestFile, digest);
        return new Resolution(digest, source, Optional.of(key));
    }

    private static String newKey() {
        byte[] random = new byte[KEY_BYTES];
        RANDOM.nextBytes(random);
        return KEY_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    }

    private static String requireStrong(String key, String origin) {
        String trimmed = key.strip();
        if (trimmed.length() < MIN_SUPPLIED_KEY_LENGTH) {
            throw new IllegalArgumentException(
                    origin + " must be at least " + MIN_SUPPLIED_KEY_LENGTH + " characters, got " + trimmed.length());
        }
        return trimmed;
    }

    private static byte[] readDigest(Path digestFile) {
        String content;
        try {
            content = Files.readString(digestFile, StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new IllegalStateException("Could not read the API key digest at " + digestFile, e);
        }
        if (!DIGEST_HEX.matcher(content).matches()) {
            throw new IllegalStateException("The API key digest at " + digestFile
                    + " is not a SHA-256 hex digest; delete it or start with --rotate-api-key");
        }
        return HEX.parseHex(content);
    }

    private static void writeDigest(Path digestFile, byte[] digest) {
        try {
            SecureFiles.writeOwnerOnly(digestFile, HEX.formatHex(digest) + System.lineSeparator());
        } catch (IOException e) {
            throw new IllegalStateException("Could not save the API key digest to " + digestFile, e);
        }
    }
}
