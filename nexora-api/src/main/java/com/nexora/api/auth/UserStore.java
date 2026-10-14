package com.nexora.api.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dashboard accounts, one {@code username:iterations$salt$hash} line per user in an owner-only
 * file, in the spirit of {@code /etc/shadow}. Passwords themselves are never stored.
 *
 * <p>{@code nexora user} edits the file while {@code nexora observe} may be running. The server
 * notices the change on its next lookup, so adding, removing or re-keying a user takes effect
 * without a restart, and a removed user or changed password ends that user's sessions.
 */
public final class UserStore {

    public static final int MIN_PASSWORD_LENGTH = 12;
    public static final int MAX_PASSWORD_LENGTH = 128;

    private static final Logger log = LoggerFactory.getLogger(UserStore.class);
    // Lowercase, starts alphanumeric, 3 to 32 characters; ':' can never appear, so lines always split cleanly.
    private static final Pattern USERNAME = Pattern.compile("[a-z0-9][a-z0-9._-]{2,31}");
    private static final char SEPARATOR = ':';

    private final Path file;
    private final PasswordHasher hasher;
    private volatile Snapshot snapshot = Snapshot.EMPTY;

    /**
     * The parsed file plus what identifies its version. Saves are atomic renames, so the file key
     * (device and inode, where the platform has one) changes on every save even when the size and a
     * coarse timestamp do not, as with a password change inside the same second.
     */
    private record Snapshot(Map<String, PasswordHasher.Hash> users, FileTime modified, long size, Object fileKey) {
        static final Snapshot EMPTY = new Snapshot(Map.of(), null, -1, null);

        boolean sameVersion(BasicFileAttributes attrs) {
            return attrs.lastModifiedTime().equals(modified)
                    && attrs.size() == size
                    && Objects.equals(attrs.fileKey(), fileKey);
        }
    }

    /**
     * @throws IllegalStateException if the file exists but cannot be read or holds a malformed line
     */
    public UserStore(Path file, PasswordHasher hasher) {
        this.file = Objects.requireNonNull(file, "file");
        this.hasher = Objects.requireNonNull(hasher, "hasher");
        this.snapshot = load();
    }

    public Path file() {
        return file;
    }

    public PasswordHasher hasher() {
        return hasher;
    }

    /** Usernames are case-insensitive; this is the form they are stored and logged in. */
    public static String normalize(String username) {
        return username == null ? "" : username.strip().toLowerCase(Locale.ROOT);
    }

    /** @throws IllegalArgumentException if {@code username} is not 3 to 32 of a-z, 0-9, '.', '_', '-' */
    public static String requireValidUsername(String username) {
        String normalized = normalize(username);
        if (!USERNAME.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Username must be 3 to 32 characters of a-z, 0-9, '.', '_' or '-',"
                    + " starting with a letter or digit");
        }
        return normalized;
    }

    /** @throws IllegalArgumentException if the password is too short, too long, or the username itself */
    public static void requireAcceptablePassword(String username, char[] password) {
        if (password.length < MIN_PASSWORD_LENGTH || password.length > MAX_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("Password must be " + MIN_PASSWORD_LENGTH + " to " + MAX_PASSWORD_LENGTH
                    + " characters, got " + password.length);
        }
        if (normalize(new String(password)).equals(normalize(username))) {
            throw new IllegalArgumentException("Password must not be the username");
        }
    }

    public List<String> usernames() {
        return List.copyOf(current().users().keySet());
    }

    public boolean isEmpty() {
        return current().users().isEmpty();
    }

    Optional<PasswordHasher.Hash> find(String username) {
        return Optional.ofNullable(current().users().get(normalize(username)));
    }

    /** @throws IllegalArgumentException if the user exists, or the username or password is invalid */
    public synchronized void add(String username, char[] password) {
        String name = requireValidUsername(username);
        requireAcceptablePassword(name, password);
        Map<String, PasswordHasher.Hash> users = new TreeMap<>(reloadForWrite().users());
        if (users.containsKey(name)) {
            throw new IllegalArgumentException("User '" + name + "' already exists; use passwd to change the password");
        }
        users.put(name, hasher.hash(password));
        save(users);
    }

    /** Replaces the password; the user's existing sessions stop working. */
    public synchronized void setPassword(String username, char[] password) {
        String name = normalize(username);
        requireAcceptablePassword(name, password);
        Map<String, PasswordHasher.Hash> users = new TreeMap<>(reloadForWrite().users());
        if (!users.containsKey(name)) {
            throw new IllegalArgumentException("No user '" + name + "'");
        }
        users.put(name, hasher.hash(password));
        save(users);
    }

    /** Deletes the account; the user's existing sessions stop working. */
    public synchronized void remove(String username) {
        String name = normalize(username);
        Map<String, PasswordHasher.Hash> users = new TreeMap<>(reloadForWrite().users());
        if (users.remove(name) == null) {
            throw new IllegalArgumentException("No user '" + name + "'");
        }
        save(users);
    }

    /** Re-reads the file when its timestamp or size changed; a broken edit keeps the last good copy. */
    private Snapshot current() {
        Snapshot known = snapshot;
        try {
            if (known.sameVersion(Files.readAttributes(file, BasicFileAttributes.class))) {
                return known;
            }
            Snapshot fresh = load();
            snapshot = fresh;
            return fresh;
        } catch (NoSuchFileException e) {
            snapshot = Snapshot.EMPTY;
            return Snapshot.EMPTY;
        } catch (IOException | IllegalStateException e) {
            log.error("Could not reload dashboard users, keeping the previous list file={}", file, e);
            return known;
        }
    }

    private Snapshot reloadForWrite() {
        Snapshot fresh = load();
        snapshot = fresh;
        return fresh;
    }

    private Snapshot load() {
        if (!Files.exists(file)) {
            return Snapshot.EMPTY;
        }
        try {
            BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            Map<String, PasswordHasher.Hash> users = new TreeMap<>();
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                users.put(parseUsername(line, i + 1), parseHash(line, i + 1));
            }
            return new Snapshot(
                    Collections.unmodifiableMap(users), attrs.lastModifiedTime(), attrs.size(), attrs.fileKey());
        } catch (IOException e) {
            throw new IllegalStateException("Could not read dashboard users from " + file, e);
        }
    }

    private String parseUsername(String line, int lineNumber) {
        int sep = line.indexOf(SEPARATOR);
        String name = sep < 0 ? "" : line.substring(0, sep);
        if (!USERNAME.matcher(name).matches()) {
            throw new IllegalStateException("Malformed user entry at " + file + ":" + lineNumber);
        }
        return name;
    }

    private PasswordHasher.Hash parseHash(String line, int lineNumber) {
        try {
            return PasswordHasher.Hash.decode(line.substring(line.indexOf(SEPARATOR) + 1));
        } catch (IllegalArgumentException e) {
            // The message names only the location: the line itself holds hash material.
            throw new IllegalStateException("Malformed password hash at " + file + ":" + lineNumber);
        }
    }

    private void save(Map<String, PasswordHasher.Hash> users) {
        List<String> lines = new ArrayList<>();
        lines.add("# Nexora dashboard users: username:iterations$salt$hash. Manage with `nexora user`.");
        users.forEach((name, hash) -> lines.add(name + SEPARATOR + hash.encode()));
        try {
            SecureFiles.writeOwnerOnly(file, String.join(System.lineSeparator(), lines) + System.lineSeparator());
        } catch (IOException e) {
            throw new IllegalStateException("Could not save dashboard users to " + file, e);
        }
        snapshot = load();
    }
}
