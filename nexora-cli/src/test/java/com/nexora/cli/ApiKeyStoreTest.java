package com.nexora.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApiKeyStoreTest {

    private static final String STRONG_KEY = "supplied-key-0123456789abcdefghijkl";

    @TempDir
    Path home;

    private Path digestFile() {
        return home.resolve(".nexora").resolve("observe-api-key.sha256");
    }

    @Test
    void firstRunGeneratesAKeyAndSavesOnlyItsDigest() throws Exception {
        ApiKeyStore.Resolution resolution = ApiKeyStore.resolve(null, null, digestFile());

        String key = resolution.newKey().orElseThrow();
        String saved = Files.readString(digestFile()).strip();
        assertEquals(ApiKeyStore.Source.GENERATED, resolution.source());
        assertTrue(key.startsWith(ApiKeyStore.KEY_PREFIX), key);
        assertEquals(ApiKeyStore.KEY_PREFIX.length() + 43, key.length());
        assertEquals(HexFormat.of().formatHex(ApiKeyStore.digest(key)), saved);
        assertFalse(saved.contains(key.substring(ApiKeyStore.KEY_PREFIX.length())));
    }

    @Test
    void digestFileAndItsDirectoryAreOwnerOnly() throws Exception {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));

        ApiKeyStore.resolve(null, null, digestFile());

        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(digestFile())));
        assertEquals(
                "rwx------",
                PosixFilePermissions.toString(
                        Files.getPosixFilePermissions(digestFile().getParent())));
    }

    @Test
    void laterRunsReadTheDigestAndCannotShowTheKey() {
        ApiKeyStore.Resolution first = ApiKeyStore.resolve(null, null, digestFile());

        ApiKeyStore.Resolution second = ApiKeyStore.resolve(null, null, digestFile());

        assertEquals(ApiKeyStore.Source.FILE, second.source());
        assertTrue(second.newKey().isEmpty());
        assertArrayEquals(first.digest(), second.digest());
    }

    @Test
    void flagWinsOverEnvironmentAndFile() {
        ApiKeyStore.resolve(null, null, digestFile());

        ApiKeyStore.Resolution resolution =
                ApiKeyStore.resolve(STRONG_KEY, "env-key-0123456789abcdefghijklmnopq", digestFile());

        assertEquals(ApiKeyStore.Source.FLAG, resolution.source());
        assertArrayEquals(ApiKeyStore.digest(STRONG_KEY), resolution.digest());
    }

    @Test
    void environmentIsUsedWithoutTouchingTheFile() {
        ApiKeyStore.Resolution resolution = ApiKeyStore.resolve(null, STRONG_KEY, digestFile());

        assertEquals(ApiKeyStore.Source.ENVIRONMENT, resolution.source());
        assertArrayEquals(ApiKeyStore.digest(STRONG_KEY), resolution.digest());
        assertFalse(Files.exists(digestFile()));
    }

    @Test
    void blankEnvironmentFallsThroughToTheFile() {
        assertEquals(
                ApiKeyStore.Source.GENERATED,
                ApiKeyStore.resolve(null, "  ", digestFile()).source());
    }

    @Test
    void shortSuppliedKeysAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> ApiKeyStore.resolve("short", null, digestFile()));
        assertThrows(IllegalArgumentException.class, () -> ApiKeyStore.resolve(null, "short", digestFile()));
    }

    @Test
    void rotationReplacesTheDigestSoTheOldKeyStopsWorking() {
        String oldKey = ApiKeyStore.resolve(null, null, digestFile()).newKey().orElseThrow();

        ApiKeyStore.Resolution rotated = ApiKeyStore.rotate(digestFile());

        String newKey = rotated.newKey().orElseThrow();
        ApiAuth auth = new ApiAuth(ApiKeyStore.resolve(null, null, digestFile()).digest());
        assertEquals(ApiKeyStore.Source.ROTATED, rotated.source());
        assertNotEquals(oldKey, newKey);
        assertFalse(auth.isApiKey(oldKey));
        assertTrue(auth.isApiKey(newKey));
    }

    @Test
    void corruptDigestFileFailsWithRecoveryAdvice() throws Exception {
        Files.createDirectories(digestFile().getParent());
        Files.writeString(digestFile(), "not a digest");

        IllegalStateException error =
                assertThrows(IllegalStateException.class, () -> ApiKeyStore.resolve(null, null, digestFile()));

        assertTrue(error.getMessage().contains("--rotate-api-key"), error.getMessage());
    }

    @Test
    void generatedKeysDiffer() {
        Path other = home.resolve("other.sha256");

        assertNotEquals(
                ApiKeyStore.resolve(null, null, digestFile()).newKey(),
                ApiKeyStore.resolve(null, null, other).newKey());
    }
}
