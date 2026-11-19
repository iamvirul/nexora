package me.virul.nexora.spi;

import me.virul.nexora.core.capability.CapabilityRequest;
import me.virul.nexora.core.capability.CapabilityResult;

/**
 * Contract all capability implementations must fulfill.
 * Implementations must be thread-safe; the engine may call execute() concurrently.
 */
public interface Capability {

    /**
     * Performs one attempt of a step.
     *
     * <p>Report an expected failure by returning {@link CapabilityResult#failure(String, String)}; the
     * engine's default retry policy decides whether to try again. An exception thrown from here is treated as a
     * failure too. Capabilities with side effects should deduplicate on
     * {@link CapabilityRequest#idempotencyKey()}, as a retry or a replay sends the same key again.
     *
     * @param request the step inputs and call metadata
     * @return the outcome; never null
     */
    CapabilityResult execute(CapabilityRequest request);
}
