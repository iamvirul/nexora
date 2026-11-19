package me.virul.nexora.benchmarks;

import java.util.Map;
import me.virul.nexora.core.capability.CapabilityResult;
import me.virul.nexora.spi.Capability;

/** Capabilities that do no work of their own, so a benchmark measures only the engine around them. */
final class Capabilities {

    static final String NOOP = "bench.noop";

    private static final CapabilityResult OK = CapabilityResult.success(Map.of("ok", true));

    static final Capability NOOP_CAPABILITY = request -> OK;

    private Capabilities() {}
}
