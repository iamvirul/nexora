package com.nexora.api.auth;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class LoginThrottleTest {

    private final MutableClock clock = new MutableClock();
    private final LoginThrottle throttle = new LoginThrottle(clock);

    @Test
    void usernameLocksAfterRepeatedFailuresAndUnlocksLater() {
        for (int i = 0; i < LoginThrottle.MAX_FAILURES; i++) {
            assertTrue(throttle.retryAfter("alice", "c" + i).isEmpty());
            throttle.recordFailure("alice");
        }

        assertTrue(throttle.retryAfter("alice", "another-client").isPresent());
        clock.advance(LoginThrottle.LOCKOUT.plusSeconds(1));
        assertTrue(throttle.retryAfter("alice", "another-client").isEmpty());
    }

    @Test
    void successResetsTheCount() {
        for (int i = 0; i < LoginThrottle.MAX_FAILURES - 1; i++) {
            throttle.recordFailure("alice");
        }
        throttle.recordSuccess("alice");
        throttle.recordFailure("alice");

        assertTrue(throttle.retryAfter("alice", "c").isEmpty());
    }

    @Test
    void clientIsLimitedAcrossUsernames() {
        for (int i = 0; i < LoginThrottle.MAX_ATTEMPTS_PER_WINDOW; i++) {
            throttle.recordAttempt("10.0.0.1");
        }

        assertTrue(throttle.retryAfter("anyone", "10.0.0.1").isPresent());
        assertTrue(throttle.retryAfter("anyone", "10.0.0.2").isEmpty());
        clock.advance(LoginThrottle.WINDOW.plusSeconds(1));
        assertTrue(throttle.retryAfter("anyone", "10.0.0.1").isEmpty());
    }

    @Test
    void floodOfMadeUpNamesDoesNotResetARealAccountsCount() {
        for (int i = 0; i < LoginThrottle.MAX_FAILURES - 1; i++) {
            throttle.recordFailure("alice");
        }
        // Within the same minute: both a burst of clients and a burst of one-off usernames.
        for (int i = 0; i < LoginThrottle.MAX_TRACKED + 10; i++) {
            throttle.recordAttempt("client-" + i);
            throttle.recordFailure("made-up-" + i);
        }
        throttle.recordFailure("alice");

        assertTrue(throttle.retryAfter("alice", "c").isPresent());
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-14T10:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
