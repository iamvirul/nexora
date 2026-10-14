package com.nexora.api.auth;

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
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UserStoreTest {

    private static final char[] PASSWORD = "correct horse battery".toCharArray();

    @TempDir
    Path dir;

    private UserStore store() {
        return new UserStore(dir.resolve("users"), new PasswordHasher(1_000));
    }

    @Test
    void addedUserIsFoundCaseInsensitivelyAndPasswordIsNotStored() throws Exception {
        UserStore store = store();

        store.add("Alice", PASSWORD);

        assertEquals(List.of("alice"), store.usernames());
        assertTrue(store.find("ALICE").isPresent());
        assertFalse(Files.readString(store.file()).contains("correct horse battery"));
    }

    @Test
    void fileIsOwnerOnly() throws Exception {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        UserStore store = store();

        store.add("alice", PASSWORD);

        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(store.file())));
    }

    @Test
    void duplicateUnknownAndInvalidInputsAreRejected() {
        UserStore store = store();
        store.add("alice", PASSWORD);

        assertThrows(IllegalArgumentException.class, () -> store.add("alice", PASSWORD));
        assertThrows(IllegalArgumentException.class, () -> store.remove("bob"));
        assertThrows(IllegalArgumentException.class, () -> store.setPassword("bob", PASSWORD));
        assertThrows(IllegalArgumentException.class, () -> store.add("a:b", PASSWORD));
        assertThrows(IllegalArgumentException.class, () -> store.add("ab", PASSWORD));
        assertThrows(IllegalArgumentException.class, () -> store.add("carol", "short".toCharArray()));
        assertThrows(
                IllegalArgumentException.class, () -> store.add("carol-the-admin", "carol-the-admin".toCharArray()));
    }

    @Test
    void passwordChangeGivesANewFingerprint() {
        UserStore store = store();
        store.add("alice", PASSWORD);
        String before = store.find("alice").orElseThrow().fingerprint();

        store.setPassword("alice", "another long password".toCharArray());

        assertNotEquals(before, store.find("alice").orElseThrow().fingerprint());
    }

    @Test
    void aRunningStoreSeesChangesMadeByAnotherProcess() {
        UserStore server = store();
        UserStore cli = store();

        cli.add("alice", PASSWORD);
        assertTrue(server.find("alice").isPresent());

        cli.remove("alice");
        assertTrue(server.find("alice").isEmpty());
    }

    @Test
    void passwordChangeInTheSameSecondIsStillSeen() {
        UserStore server = store();
        UserStore cli = store();
        cli.add("alice", PASSWORD);
        String before = server.find("alice").orElseThrow().fingerprint();

        cli.setPassword("alice", "another long password".toCharArray());

        assertNotEquals(before, server.find("alice").orElseThrow().fingerprint());
    }

    @Test
    void malformedFileFailsAtStartupNamingTheLineButNotItsContent() throws Exception {
        Path file = dir.resolve("users");
        Files.writeString(file, "alice:not$a$hash-secretish\n");

        IllegalStateException error =
                assertThrows(IllegalStateException.class, () -> new UserStore(file, new PasswordHasher(1_000)));

        assertTrue(error.getMessage().endsWith("users:1"), error.getMessage());
        assertFalse(error.getMessage().contains("secretish"));
    }

    @Test
    void missingFileMeansNoUsers() {
        assertTrue(store().isEmpty());
    }
}
