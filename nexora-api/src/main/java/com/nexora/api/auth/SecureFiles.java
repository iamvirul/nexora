package com.nexora.api.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;

/** Owner-only, crash-safe writes for credential files: the API key digest, the user store, CLI logins. */
public final class SecureFiles {

    private SecureFiles() {}

    /**
     * Writes {@code content} through a temp file and an atomic move, so a crash never leaves a
     * half-written file. On POSIX systems the directory is created {@code rwx------} and the file
     * {@code rw-------}.
     */
    public static void writeOwnerOnly(Path file, String content) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        boolean posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
        Files.createDirectories(dir, posix ? ownerOnly("rwx------") : new FileAttribute<?>[0]);
        Path temp =
                Files.createTempFile(dir, ".nexora", ".tmp", posix ? ownerOnly("rw-------") : new FileAttribute<?>[0]);
        try {
            Files.writeString(temp, content, StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static FileAttribute<?>[] ownerOnly(String permissions) {
        return new FileAttribute<?>[] {
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(permissions))
        };
    }
}
