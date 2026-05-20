package com.nexora.spi;

/**
 * Factory for a Planner instance, following the same provider pattern as CapabilityProvider.
 * Plugins return a list of these from plannerProviders().
 */
public interface PlannerProvider {
    PlannerDescriptor descriptor();
    Planner create(PluginContext context);
}
