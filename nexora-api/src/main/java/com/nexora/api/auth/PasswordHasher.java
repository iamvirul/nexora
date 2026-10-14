package com.nexora.api.auth;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Slow, salted password hashing with PBKDF2-HMAC-SHA256, which every JDK ships.
 *
 * <p>Unlike API keys, passwords are chosen by people and can be guessed, so each check must be
 * expensive. The iteration count is stored with every hash, so it can be raised later without
 * invalidating existing passwords.
 */
public final class PasswordHasher {

    /** OWASP's 2023 recommendation for PBKDF2-HMAC-SHA256. */
    public static final int DEFAULT_ITERATIONS = 600_000;

    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int SALT_BYTES = 16;
    private static final int HASH_BITS = 256;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getDecoder();

    /** A stored password: the iteration count, salt and derived hash, all needed to check a guess. */
    public record Hash(int iterations, byte[] salt, byte[] hash) {

        public Hash {
            if (iterations < 1) {
                throw new IllegalArgumentException("iterations must be positive, got " + iterations);
            }
            salt = salt.clone();
            hash = hash.clone();
        }

        @Override
        public byte[] salt() {
            return salt.clone();
        }

        @Override
        public byte[] hash() {
            return hash.clone();
        }

        /** Changes whenever the password is set again, since every hash gets a fresh salt. */
        String fingerprint() {
            return ENCODER.encodeToString(salt);
        }

        String encode() {
            return iterations + "$" + ENCODER.encodeToString(salt) + "$" + ENCODER.encodeToString(hash);
        }

        /** @throws IllegalArgumentException if {@code encoded} is not {@code iterations$salt$hash} */
        static Hash decode(String encoded) {
            String[] parts = encoded.split("\\$", -1);
            if (parts.length != 3) {
                throw new IllegalArgumentException("expected iterations$salt$hash");
            }
            return new Hash(Integer.parseInt(parts[0]), DECODER.decode(parts[1]), DECODER.decode(parts[2]));
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Hash that
                    && iterations == that.iterations
                    && Arrays.equals(salt, that.salt)
                    && Arrays.equals(hash, that.hash);
        }

        @Override
        public int hashCode() {
            return 31 * (31 * iterations + Arrays.hashCode(salt)) + Arrays.hashCode(hash);
        }

        @Override
        public String toString() {
            // Never print hash material, even by accident in a log line.
            return "Hash[iterations=" + iterations + "]";
        }
    }

    private final int iterations;
    /** Checked against for unknown users, so a missing account costs the same time as a wrong password. */
    private final Hash decoy;

    public PasswordHasher() {
        this(DEFAULT_ITERATIONS);
    }

    /** Fewer iterations are only for tests, where 600,000 per check would make suites slow. */
    PasswordHasher(int iterations) {
        this.iterations = iterations;
        this.decoy = hash("decoy password that matches nothing".toCharArray());
    }

    public Hash hash(char[] password) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        return new Hash(iterations, salt, derive(password, salt, iterations));
    }

    /** Recomputes with the stored salt and iterations and compares in constant time. */
    public boolean matches(char[] password, Hash stored) {
        byte[] candidate = derive(password, stored.salt(), stored.iterations());
        return MessageDigest.isEqual(candidate, stored.hash());
    }

    /** Spends the same effort as {@link #matches} without a real account behind it. */
    void burnEquivalentTime(char[] password) {
        matches(password, decoy);
    }

    private static byte[] derive(char[] password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, HASH_BITS);
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            // Every Java platform is required to provide PBKDF2WithHmacSHA256.
            throw new IllegalStateException(ALGORITHM + " is unavailable", e);
        } finally {
            spec.clearPassword();
        }
    }
}
