package me.virul.nexora.retry;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public final class DefaultRetryPolicyRegistry implements RetryPolicyRegistry {

    private final ConcurrentHashMap<String, RetryPolicy> policies = new ConcurrentHashMap<>();
    private volatile RetryPolicy defaultPolicy = RetryPolicy.noRetry();

    @Override
    public void register(String policyId, RetryPolicy policy) {
        Objects.requireNonNull(policyId, "policyId must not be null");
        Objects.requireNonNull(policy, "policy must not be null");
        policies.put(policyId, policy);
    }

    @Override
    public RetryPolicy resolve(String policyId) {
        if (policyId == null) return defaultPolicy;
        return policies.getOrDefault(policyId, defaultPolicy);
    }

    @Override
    public void setDefault(RetryPolicy policy) {
        this.defaultPolicy = Objects.requireNonNull(policy, "policy must not be null");
    }
}
