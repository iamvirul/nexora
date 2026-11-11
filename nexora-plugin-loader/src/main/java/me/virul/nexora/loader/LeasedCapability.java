package me.virul.nexora.loader;

import java.util.Objects;
import me.virul.nexora.core.capability.CapabilityRequest;
import me.virul.nexora.core.capability.CapabilityResult;
import me.virul.nexora.spi.Capability;
import me.virul.nexora.spi.CapabilityRegistry;

/**
 * A capability from a JAR plugin, registered in place of the plugin's own instance so that every
 * invocation holds a lease on the plugin version that serves it.
 *
 * <p>An invocation that looked the capability up just before a reload, but reaches it after the old
 * version was released, is handed to whatever version is registered under the same id now.
 */
final class LeasedCapability implements Capability {

    /** Bounds the hand-over when several reloads land during one invocation. */
    private static final int MAX_HANDOVERS = 3;

    static final String NOT_FOUND_CODE = "CAPABILITY_NOT_FOUND";
    static final String RELOADING_CODE = "CAPABILITY_RELOADING";

    private final String capabilityId;
    private final Capability delegate;
    private final InvocationLeases leases;
    private final CapabilityRegistry registry;

    LeasedCapability(String capabilityId, Capability delegate, InvocationLeases leases, CapabilityRegistry registry) {
        this.capabilityId = Objects.requireNonNull(capabilityId, "capabilityId must not be null");
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.leases = Objects.requireNonNull(leases, "leases must not be null");
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
    }

    @Override
    public CapabilityResult execute(CapabilityRequest request) {
        LeasedCapability target = this;
        for (int attempt = 0; attempt < MAX_HANDOVERS; attempt++) {
            if (target.leases.tryAcquire()) {
                try {
                    return target.delegate.execute(request);
                } finally {
                    target.leases.release();
                }
            }
            Capability current = registry.find(capabilityId).orElse(null);
            if (current == null) {
                return CapabilityResult.failure(NOT_FOUND_CODE, "No capability registered with id: " + capabilityId);
            }
            if (!(current instanceof LeasedCapability leased)) {
                return current.execute(request);
            }
            target = leased;
        }
        return CapabilityResult.failure(
                RELOADING_CODE, "Capability " + capabilityId + " was reloaded repeatedly during the call; retry");
    }
}
