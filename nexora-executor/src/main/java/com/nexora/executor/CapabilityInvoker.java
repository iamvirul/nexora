package com.nexora.executor;

import com.nexora.core.capability.CapabilityContract;
import com.nexora.core.capability.CapabilityRequest;
import com.nexora.core.capability.CapabilityResult;
import com.nexora.spi.Capability;
import com.nexora.spi.CapabilityDescriptor;
import com.nexora.spi.CapabilityRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Terminal node in the pipeline: resolves the capability from the registry and invokes it.
 *
 * Before invoking, it checks the capability's contract health via CapabilityContractMonitor.
 * If the primary capability is unhealthy and has a declared fallback, the call is routed
 * transparently to the fallback capability — the caller sees a normal result.
 *
 * All call outcomes (success/failure, latency) are recorded back to the monitor.
 */
public final class CapabilityInvoker {

    private static final Logger log = LoggerFactory.getLogger(CapabilityInvoker.class);

    private final CapabilityRegistry registry;
    private final CapabilityContractMonitor monitor;

    public CapabilityInvoker(CapabilityRegistry registry, CapabilityContractMonitor monitor) {
        this.registry = Objects.requireNonNull(registry);
        this.monitor = Objects.requireNonNull(monitor);
    }

    public CapabilityResult invoke(CapabilityRequest request) {
        String primaryId = request.capabilityId();
        String targetId = resolveTarget(primaryId);

        Capability capability = registry.find(targetId).orElse(null);

        if (capability == null) {
            monitor.recordFailure(primaryId, Duration.ZERO);
            return CapabilityResult.failure(
                    "CAPABILITY_NOT_FOUND",
                    "No capability registered with id: " + targetId
            );
        }

        Instant start = Instant.now();
        CapabilityResult result = capability.execute(request);
        Duration elapsed = Duration.between(start, Instant.now());

        if (result.succeeded()) {
            monitor.recordSuccess(primaryId, elapsed);
        } else {
            monitor.recordFailure(primaryId, elapsed);
        }

        return result;
    }

    private String resolveTarget(String capabilityId) {
        CapabilityDescriptor descriptor = registry.findDescriptor(capabilityId).orElse(null);
        if (descriptor == null) return capabilityId;

        CapabilityContract contract = descriptor.contract();
        if (contract.hasFallback() && !monitor.isHealthy(capabilityId, contract)) {
            log.warn("Capability {} is unhealthy — routing to fallback {}",
                    capabilityId, contract.fallbackCapabilityId());
            return contract.fallbackCapabilityId();
        }

        return capabilityId;
    }
}
