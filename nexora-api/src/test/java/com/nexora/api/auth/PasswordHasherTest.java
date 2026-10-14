package com.nexora.api.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PasswordHasherTest {

    private final PasswordHasher hasher = new PasswordHasher(1_000);

    @Test
    void matchesTheRightPasswordOnly() {
        PasswordHasher.Hash stored = hasher.hash("correct horse battery".toCharArray());

        assertTrue(hasher.matches("correct horse battery".toCharArray(), stored));
        assertFalse(hasher.matches("correct horse batterY".toCharArray(), stored));
    }

    @Test
    void samePasswordGetsADifferentSaltEachTime() {
        PasswordHasher.Hash first = hasher.hash("correct horse battery".toCharArray());
        PasswordHasher.Hash second = hasher.hash("correct horse battery".toCharArray());

        assertNotEquals(first, second);
        assertNotEquals(first.fingerprint(), second.fingerprint());
    }

    @Test
    void encodingRoundTripsAndKeepsTheIterationCount() {
        PasswordHasher.Hash stored = hasher.hash("correct horse battery".toCharArray());

        PasswordHasher.Hash decoded = PasswordHasher.Hash.decode(stored.encode());

        assertEquals(stored, decoded);
        assertEquals(1_000, decoded.iterations());
        assertTrue(new PasswordHasher(5).matches("correct horse battery".toCharArray(), decoded));
    }

    @Test
    void malformedEncodingIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> PasswordHasher.Hash.decode("not-a-hash"));
        assertThrows(IllegalArgumentException.class, () -> PasswordHasher.Hash.decode("0$AAAA$AAAA"));
    }

    @Test
    void toStringNeverShowsHashMaterial() {
        PasswordHasher.Hash stored = hasher.hash("correct horse battery".toCharArray());

        assertEquals("Hash[iterations=1000]", stored.toString());
    }

    @Test
    void defaultsToOwaspIterationCount() {
        assertEquals(600_000, PasswordHasher.DEFAULT_ITERATIONS);
    }
}
