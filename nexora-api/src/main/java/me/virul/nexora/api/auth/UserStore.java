package me.virul.nexora.api.auth;

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
import me.virul.nexora.core.tenant.TenantId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dashboard accounts, one {@code username:iterations$salt$hash[:tenant]} line per user in an
 * owner-only file, in the spirit of {@code /etc/shadow}. Passwords themselves are never stored.
 *
 * <p>Each user belongs to one tenant, and on a multi-tenant server can only reach that tenant's
 * executions. A line without a tenant belongs to {@link TenantId#DEFAULT}, so files written
 * before tenants existed keep working, and default-tenant users are still written that way.
 *
 * <p>{@code nexora user} edits the file while {@code nexora observe} may be running. The server
 * notices the change on its next lookup, so adding, removing or re-keying a user takes effect
 * without a restart, and a removed user or changed password ends that user's sessions.
 */
public final class UserStore {

    /** The shortest password accepted, in characters. */
    public static final int MIN_PASSWORD_LENGTH = 12;

    /** The longest password accepted, in characters. */
    public static final int MAX_PASSWORD_LENGTH = 128;

    private static final Logger log = LoggerFactory.getLogger(UserStore.class);
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
    private record Account(PasswordHasher.Hash hash, String tenantId) {}

    private record Snapshot(Map<String, Account> users, FileTime modified, long size, Object fileKey) {
        static final Snapshot EMPTY = new Snapshot(Map.of(), null, -1, null);

        boolean sameVersion(BasicFileAttributes attrs) {
            return attrs.lastModifiedTime().equals(modified)
                    && attrs.size() == size
                    && Objects.equals(attrs.fileKey(), fileKey);
        }
    }

    /**
     * Opens the store and reads the file, if it exists.
     *
     * @param file   the users file; created on the first write
     * @param hasher hashes new passwords and checks logins
     * @throws NullPointerException  if either argument is null
     * @throws IllegalStateException if the file exists but cannot be read or holds a malformed line
     */
    public UserStore(Path file, PasswordHasher hasher) {
        this.file = Objects.requireNonNull(file, "file");
        this.hasher = Objects.requireNonNull(hasher, "hasher");
        this.snapshot = load();
    }

    /**
     * The users file.
     *
     * @return its path
     */
    public Path file() {
        return file;
    }

    /**
     * The hasher passwords are hashed and checked with.
     *
     * @return the hasher
     */
    public PasswordHasher hasher() {
        return hasher;
    }

    /**
     * Usernames are case-insensitive; this is the form they are stored and logged in.
     *
     * @param username the username as typed, or {@code null}
     * @return it stripped and lowercased; empty for {@code null}
     */
    public static String normalize(String username) {
        return username == null ? "" : username.strip().toLowerCase(Locale.ROOT);
    }

    /**
     * Normalizes a username and checks it can be stored.
     *
     * @param username the username as typed
     * @return the normalized username
     * @throws IllegalArgumentException if {@code username} is not 3 to 32 of a-z, 0-9, '.', '_', '-'
     */
    public static String requireValidUsername(String username) {
        String normalized = normalize(username);
        if (!USERNAME.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Username must be 3 to 32 characters of a-z, 0-9, '.', '_' or '-',"
                    + " starting with a letter or digit");
        }
        return normalized;
    }

    /**
     * Checks a new password against the password rules.
     *
     * @param username the user the password is for
     * @param password the new password
     * @throws IllegalArgumentException if the password is too short, too long, or the username itself
     */
    public static void requireAcceptablePassword(String username, char[] password) {
        if (password.length < MIN_PASSWORD_LENGTH || password.length > MAX_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("Password must be " + MIN_PASSWORD_LENGTH + " to " + MAX_PASSWORD_LENGTH
                    + " characters, got " + password.length);
        }
        if (normalize(new String(password)).equals(normalize(username))) {
            throw new IllegalArgumentException("Password must not be the username");
        }
    }

    /**
     * Every username, as the file holds them now. If a changed file is malformed or cannot be read,
     * the failure is logged and the last list read successfully is returned. If the file has been
     * deleted, the list is empty.
     *
     * @return the normalized usernames, sorted
     */
    public List<String> usernames() {
        return List.copyOf(current().users().keySet());
    }

    /**
     * Whether the store has no users. If a changed file is malformed or cannot be read, the failure
     * is logged and the last list read successfully is used. If the file has been deleted, the store
     * is empty.
     *
     * @return {@code true} when there are none
     */
    public boolean isEmpty() {
        return current().users().isEmpty();
    }

    Optional<PasswordHasher.Hash> find(String username) {
        return account(username).map(Account::hash);
    }

    /**
     * The tenant {@code username} belongs to, if the user exists.
     *
     * @param username the username, normalized before lookup
     * @return the tenant id, or empty
     */
    public Optional<String> tenantOf(String username) {
        return account(username).map(Account::tenantId);
    }

    private Optional<Account> account(String username) {
        return Optional.ofNullable(current().users().get(normalize(username)));
    }

    /**
     * Adds a user of the {@link TenantId#DEFAULT} tenant.
     *
     * @param username the new username
     * @param password the password; not cleared by this method
     * @throws IllegalArgumentException if the user exists, or the username or password is invalid
     * @throws IllegalStateException    if the file cannot be read or written
     */
    public synchronized void add(String username, char[] password) {
        add(username, password, TenantId.DEFAULT);
    }

    /**
     * Adds a user of a tenant.
     *
     * @param username the new username
     * @param password the password; not cleared by this method
     * @param tenantId the tenant the user belongs to
     * @throws IllegalArgumentException if the user exists, or the username, password or tenant is invalid
     * @throws IllegalStateException    if the file cannot be read or written
     */
    public synchronized void add(String username, char[] password, String tenantId) {
        String name = requireValidUsername(username);
        requireAcceptablePassword(name, password);
        TenantId.requireValid(tenantId);
        Map<String, Account> users = new TreeMap<>(reloadForWrite().users());
        if (users.containsKey(name)) {
            throw new IllegalArgumentException("User '" + name + "' already exists; use passwd to change the password");
        }
        users.put(name, new Account(hasher.hash(password), tenantId));
        save(users);
    }

    /**
     * Moves the user to another tenant; the user's existing sessions stop working.
     *
     * @param username the user
     * @param tenantId the new tenant
     * @throws IllegalArgumentException if there is no such user or the tenant id is invalid
     * @throws IllegalStateException    if the file cannot be read or written
     */
    public synchronized void setTenant(String username, String tenantId) {
        String name = normalize(username);
        TenantId.requireValid(tenantId);
        Map<String, Account> users = new TreeMap<>(reloadForWrite().users());
        Account existing = users.get(name);
        if (existing == null) {
            throw new IllegalArgumentException("No user '" + name + "'");
        }
        users.put(name, new Account(existing.hash(), tenantId));
        save(users);
    }

    /**
     * Replaces the password; the user's existing sessions stop working.
     *
     * @param username the user
     * @param password the new password; not cleared by this method
     * @throws IllegalArgumentException if there is no such user or the password is not acceptable
     * @throws IllegalStateException    if the file cannot be read or written
     */
    public synchronized void setPassword(String username, char[] password) {
        String name = normalize(username);
        requireAcceptablePassword(name, password);
        Map<String, Account> users = new TreeMap<>(reloadForWrite().users());
        Account existing = users.get(name);
        if (existing == null) {
            throw new IllegalArgumentException("No user '" + name + "'");
        }
        users.put(name, new Account(hasher.hash(password), existing.tenantId()));
        save(users);
    }

    /**
     * Deletes the account; the user's existing sessions stop working.
     *
     * @param username the user
     * @throws IllegalArgumentException if there is no such user
     * @throws IllegalStateException    if the file cannot be read or written
     */
    public synchronized void remove(String username) {
        String name = normalize(username);
        Map<String, Account> users = new TreeMap<>(reloadForWrite().users());
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
            Map<String, Account> users = new TreeMap<>();
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] fields = line.split(String.valueOf(SEPARATOR), -1);
                if (fields.length < 2 || fields.length > 3) {
                    throw new IllegalStateException("Malformed user entry at " + file + ":" + (i + 1));
                }
                users.put(
                        parseUsername(fields[0], i + 1),
                        new Account(parseHash(fields[1], i + 1), parseTenant(fields, i + 1)));
            }
            return new Snapshot(
                    Collections.unmodifiableMap(users), attrs.lastModifiedTime(), attrs.size(), attrs.fileKey());
        } catch (IOException e) {
            throw new IllegalStateException("Could not read dashboard users from " + file, e);
        }
    }

    private String parseUsername(String name, int lineNumber) {
        if (!USERNAME.matcher(name).matches()) {
            throw new IllegalStateException("Malformed user entry at " + file + ":" + lineNumber);
        }
        return name;
    }

    private PasswordHasher.Hash parseHash(String encoded, int lineNumber) {
        try {
            return PasswordHasher.Hash.decode(encoded);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Malformed password hash at " + file + ":" + lineNumber);
        }
    }

    private String parseTenant(String[] fields, int lineNumber) {
        if (fields.length < 3) {
            return TenantId.DEFAULT;
        }
        if (!TenantId.isValid(fields[2])) {
            throw new IllegalStateException("Malformed tenant at " + file + ":" + lineNumber);
        }
        return fields[2];
    }

    private void save(Map<String, Account> users) {
        List<String> lines = new ArrayList<>();
        lines.add("# Nexora dashboard users: username:iterations$salt$hash[:tenant]. Manage with `nexora user`.");
        users.forEach((name, account) -> lines.add(name
                + SEPARATOR
                + account.hash().encode()
                + (TenantId.DEFAULT.equals(account.tenantId()) ? "" : SEPARATOR + account.tenantId())));
        try {
            SecureFiles.writeOwnerOnly(file, String.join(System.lineSeparator(), lines) + System.lineSeparator());
        } catch (IOException e) {
            throw new IllegalStateException("Could not save dashboard users to " + file, e);
        }
        snapshot = load();
    }
}
