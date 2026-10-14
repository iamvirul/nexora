package com.nexora.api.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Limits password guessing two ways, both checked before any hashing so a flood of attempts cannot
 * burn the CPU on PBKDF2:
 *
 * <ul>
 *   <li>per username: {@value #MAX_FAILURES} consecutive failures lock it for {@link #LOCKOUT};
 *   <li>per client address: at most {@value #MAX_ATTEMPTS_PER_WINDOW} attempts per {@link #WINDOW}.
 * </ul>
 *
 * <p>Unknown usernames are counted exactly like real ones, so a lockout never reveals whether an
 * account exists. The API key always still works, which keeps a locked-out operator from being
 * stuck. State is in memory and bounded; a restart clears it.
 */
final class LoginThrottle {

    static final int MAX_FAILURES = 5;
    static final Duration LOCKOUT = Duration.ofMinutes(15);
    static final int MAX_ATTEMPTS_PER_WINDOW = 20;
    static final Duration WINDOW = Duration.ofMinutes(1);
    static final int MAX_TRACKED = 10_000;

    private record Failures(int count, Instant lockedUntil) {}

    private record Window(Instant start, int attempts) {}

    private final Clock clock;
    private final Map<String, Failures> failuresByUser = new ConcurrentHashMap<>();
    private final Map<String, Window> attemptsByClient = new ConcurrentHashMap<>();

    LoginThrottle(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** How long the caller must wait, if this attempt may not be made at all. */
    Optional<Duration> retryAfter(String username, String client) {
        Instant now = clock.instant();
        Failures failures = failuresByUser.get(username);
        if (failures != null && failures.lockedUntil() != null && now.isBefore(failures.lockedUntil())) {
            return Optional.of(Duration.between(now, failures.lockedUntil()));
        }
        Window window = attemptsByClient.get(client);
        if (window != null
                && now.isBefore(window.start().plus(WINDOW))
                && window.attempts() >= MAX_ATTEMPTS_PER_WINDOW) {
            return Optional.of(Duration.between(now, window.start().plus(WINDOW)));
        }
        return Optional.empty();
    }

    /** Counts an attempt against the client's window, whatever its outcome. */
    void recordAttempt(String client) {
        Instant now = clock.instant();
        prune(now);
        attemptsByClient.merge(
                client,
                new Window(now, 1),
                (old, fresh) ->
                        now.isBefore(old.start().plus(WINDOW)) ? new Window(old.start(), old.attempts() + 1) : fresh);
    }

    void recordFailure(String username) {
        Instant now = clock.instant();
        prune(now);
        failuresByUser.merge(username, new Failures(1, null), (old, fresh) -> {
            // A lock that has run out starts a new count rather than relocking on the next miss.
            int count = old.lockedUntil() != null && !now.isBefore(old.lockedUntil()) ? 1 : old.count() + 1;
            return new Failures(count, count >= MAX_FAILURES ? now.plus(LOCKOUT) : null);
        });
    }

    void recordSuccess(String username) {
        failuresByUser.remove(username);
    }

    /**
     * Keeps both maps bounded, each against its own cap so one cannot evict the other. Expired state
     * goes first. If made-up usernames still fill the failure map, the lowest counts are dropped
     * first: a flood of one-off names sits at 1, while an account under attack climbs toward the
     * lock and is the last to be forgotten. Active locks are never dropped.
     */
    private void prune(Instant now) {
        if (attemptsByClient.size() >= MAX_TRACKED) {
            attemptsByClient.values().removeIf(w -> !now.isBefore(w.start().plus(WINDOW)));
            if (attemptsByClient.size() >= MAX_TRACKED) {
                // Only lets those clients retry sooner; the per-username lock still holds.
                attemptsByClient.clear();
            }
        }
        if (failuresByUser.size() >= MAX_TRACKED) {
            failuresByUser.values().removeIf(f -> f.lockedUntil() != null && !now.isBefore(f.lockedUntil()));
            for (int count = 1; count < MAX_FAILURES && failuresByUser.size() >= MAX_TRACKED; count++) {
                int threshold = count;
                failuresByUser.values().removeIf(f -> f.lockedUntil() == null && f.count() <= threshold);
            }
        }
    }
}
