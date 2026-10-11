package com.nexora.executor;

import com.nexora.core.capability.CapabilityRequest;
import com.nexora.core.capability.CapabilityResult;
import java.util.List;
import java.util.Objects;

/**
 * Builds and executes an interceptor chain.
 * Each call to execute() constructs a fresh chain closure — stateless and thread-safe.
 */
public final class InterceptorPipeline {

    private final List<ExecutionInterceptor> interceptors;
    private final CapabilityInvoker invoker;

    public InterceptorPipeline(List<ExecutionInterceptor> interceptors, CapabilityInvoker invoker) {
        this.interceptors = List.copyOf(Objects.requireNonNull(interceptors));
        this.invoker = Objects.requireNonNull(invoker);
    }

    public CapabilityResult execute(CapabilityRequest request) {
        return buildChain(0).proceed(request);
    }

    private InterceptorChain buildChain(int index) {
        if (index >= interceptors.size()) {
            return invoker::invoke;
        }
        return request -> interceptors.get(index).intercept(request, buildChain(index + 1));
    }
}
