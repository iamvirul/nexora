package me.virul.nexora.retry;

public interface RetryPolicyRegistry {

    void register(String policyId, RetryPolicy policy);

    /** Returns the policy for the given ID, falling back to the default if absent. */
    RetryPolicy resolve(String policyId);

    void setDefault(RetryPolicy policy);
}
