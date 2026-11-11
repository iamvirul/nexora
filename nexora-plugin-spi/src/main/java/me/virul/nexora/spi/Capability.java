package me.virul.nexora.spi;

import me.virul.nexora.core.capability.CapabilityRequest;
import me.virul.nexora.core.capability.CapabilityResult;

/**
 * Contract all capability implementations must fulfill.
 * Implementations must be thread-safe; the engine may call execute() concurrently.
 */
public interface Capability {
    CapabilityResult execute(CapabilityRequest request);
}
