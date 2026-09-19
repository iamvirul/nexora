package com.nexora.executor.interceptor;

import com.nexora.core.capability.CapabilityRequest;
import com.nexora.core.capability.CapabilityResult;
import com.nexora.executor.ExecutionInterceptor;
import com.nexora.executor.InterceptorChain;
import com.nexora.retry.RetryPolicy;
import com.nexora.retry.RetryPolicyRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

public final class RetryInterceptor implements ExecutionInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RetryInterceptor.class);

    private final RetryPolicyRegistry policyRegistry;

    public RetryInterceptor(RetryPolicyRegistry policyRegistry) {
        this.policyRegistry = Objects.requireNonNull(policyRegistry);
    }

    @Override
    public CapabilityResult intercept(CapabilityRequest request, InterceptorChain chain) {
        RetryPolicy policy = policyRegistry.resolve(null); // step-level policy resolved in DagStepScheduler

        int attempt = 0;
        while (true) {
            try {
                CapabilityResult result = chain.proceed(request.withAttempt(attempt));
                if (result.succeeded() || !policy.shouldRetry(attempt, null)) {
                    return result;
                }
                log.warn("Capability returned failure, retrying. capability={} step={} attempt={} code={}",
                        request.capabilityId(), request.stepId(), attempt, result.failureCode());
                sleep(policy.backoffDelay(attempt));
                attempt++;
            } catch (Exception e) {
                if (!policy.shouldRetry(attempt, e)) throw e;
                log.warn("Capability threw, retrying. capability={} step={} attempt={}",
                        request.capabilityId(), request.stepId(), attempt, e);
                sleep(policy.backoffDelay(attempt));
                attempt++;
            }
        }
    }

    private static void sleep(java.time.Duration duration) {
        if (duration.isZero() || duration.isNegative()) return;
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Retry sleep interrupted", e);
        }
    }
}
