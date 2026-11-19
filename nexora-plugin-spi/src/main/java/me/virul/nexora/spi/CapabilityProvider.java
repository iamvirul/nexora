package me.virul.nexora.spi;

/**
 * Factory that creates a Capability instance.
 * Using a factory (rather than requiring a singleton) allows scoped or
 * per-request instances where a capability holds state.
 */
public interface CapabilityProvider {

    /**
     * Describes the capability this provider creates. Read before {@link #create(PluginContext)}.
     *
     * @return the descriptor; never null
     */
    CapabilityDescriptor descriptor();

    /**
     * Called once during plugin activation. May cache the result.
     *
     * @param context the services of the plugin that contributes this provider
     * @return the capability, registered under {@link #descriptor()}'s id; never null
     */
    @SuppressWarnings("unused")
    Capability create(PluginContext context);
}
