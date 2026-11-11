package me.virul.nexora.executor;

import me.virul.nexora.core.capability.CapabilityRequest;
import me.virul.nexora.core.capability.CapabilityResult;

@FunctionalInterface
public interface InterceptorChain {
    CapabilityResult proceed(CapabilityRequest request);
}
