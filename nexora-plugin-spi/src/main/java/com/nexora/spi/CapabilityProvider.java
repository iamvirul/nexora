package com.nexora.spi;

/**
 * Factory that creates a Capability instance.
 * Using a factory (rather than requiring a singleton) allows scoped or
 * per-request instances where a capability holds state.
 */
public interface CapabilityProvider {

    CapabilityDescriptor descriptor();

    /** Called once during plugin activation. May cache the result. */
    @SuppressWarnings("unused")
    Capability create(PluginContext context);
}
