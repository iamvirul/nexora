package com.nexora.retry;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ExponentialBackoffPolicyTest {

    @Test
    void shouldCalculateBackoff() {
        ExponentialBackoffPolicy policy = ExponentialBackoffPolicy.builder()
                .maxAttempts(3)
                .initialDelay(Duration.ofMillis(100))
                .multiplier(2.0)
                .maxDelay(Duration.ofSeconds(1))
                .build();

        Duration attempt1 = policy.backoffDelay(1);
        // 100 * 2^1 = 200, +/- 25% jitter = 150-250ms
        assertThat(attempt1).isBetween(Duration.ofMillis(150), Duration.ofMillis(250)); 
        
        Duration attempt2 = policy.backoffDelay(2);
        // 100 * 2^2 = 400ms +/- 25% jitter = 300-500ms
        assertThat(attempt2).isBetween(Duration.ofMillis(300), Duration.ofMillis(500));
        
        assertThat(policy.shouldRetry(1, new RuntimeException())).isTrue();
        assertThat(policy.shouldRetry(3, new RuntimeException())).isFalse(); // Max attempts
    }
}
