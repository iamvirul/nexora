package me.virul.nexora.spi;

/**
 * Factory for a Planner instance, following the same provider pattern as CapabilityProvider.
 * Plugins return a list of these from plannerProviders().
 */
public interface PlannerProvider {

    /**
     * Describes the planner this provider creates.
     *
     * @return the descriptor; never null
     */
    PlannerDescriptor descriptor();

    /**
     * Called once during plugin activation.
     *
     * @param context the services of the plugin that contributes this provider
     * @return the planner; never null
     */
    @SuppressWarnings("unused")
    Planner create(PluginContext context);
}
