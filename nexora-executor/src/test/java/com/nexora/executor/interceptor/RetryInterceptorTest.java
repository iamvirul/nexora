package com.nexora.executor.interceptor;

import com.nexora.core.capability.CapabilityRequest;
import com.nexora.core.capability.CapabilityResult;
import com.nexora.core.context.TraceContext;
import com.nexora.retry.DefaultRetryPolicyRegistry;
import com.nexora.retry.RetryPolicy;
import com.nexora.retry.RetryPolicyRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RetryInterceptorTest {

    @Test
    void eachRetryAttemptSeesAnIncrementingAttemptNumber() {
        RetryPolicyRegistry registry = new DefaultRetryPolicyRegistry();
        registry.setDefault(new RetryPolicy() {
            @Override public boolean shouldRetry(int attemptsMade, Throwable cause) { return attemptsMade < 2; }
            @Override public Duration backoffDelay(int attemptsMade) { return Duration.ZERO; }
        });
        RetryInterceptor interceptor = new RetryInterceptor(registry);
        List<Integer> seenAttempts = new ArrayList<>();
        CapabilityRequest request = request();

        CapabilityResult result = interceptor.intercept(request, req -> {
            seenAttempts.add(req.attemptNumber());
            return CapabilityResult.failure("TRANSIENT", "retry me");
        });

        assertThat(seenAttempts).containsExactly(0, 1, 2);
        assertThat(result.succeeded()).isFalse();
    }

    @Test
    void stopsRetryingOnceASuccessIsReturned() {
        RetryPolicyRegistry registry = new DefaultRetryPolicyRegistry();
        registry.setDefault(new RetryPolicy() {
            @Override public boolean shouldRetry(int attemptsMade, Throwable cause) { return true; }
            @Override public Duration backoffDelay(int attemptsMade) { return Duration.ZERO; }
        });
        RetryInterceptor interceptor = new RetryInterceptor(registry);
        List<Integer> seenAttempts = new ArrayList<>();
        CapabilityRequest request = request();

        CapabilityResult result = interceptor.intercept(request, req -> {
            seenAttempts.add(req.attemptNumber());
            return req.attemptNumber() == 1 ? CapabilityResult.success("ok") : CapabilityResult.failure("TRANSIENT", "retry me");
        });

        assertThat(seenAttempts).containsExactly(0, 1);
        assertThat(result.succeeded()).isTrue();
    }

    private static CapabilityRequest request() {
        return new CapabilityRequest(
                "cap", "step-1", "idem-1", Map.of(), TraceContext.root(),
                Duration.ofSeconds(1), "exec-1", 0);
    }
}
