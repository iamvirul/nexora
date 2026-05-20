package com.nexora.spi;

import java.util.List;

/**
 * Entry point every plugin JAR must implement exactly once.
 * Discovered via {@link java.util.ServiceLoader} from META-INF/services/com.nexora.spi.NexoraPlugin.
 *
 * Lifecycle: LOADED → initialize() → ACTIVE → shutdown() → INACTIVE
 */
public interface NexoraPlugin {

    PluginDescriptor descriptor();

    /**
     * Called once after the plugin ClassLoader is initialised.
     * Implementations should register capabilities, load configuration,
     * and acquire any external resources here.
     *
     * @throws PluginInitializationException if the plugin cannot start cleanly.
     */
    void initialize(PluginContext context) throws PluginInitializationException;

    /**
     * Returns all capability providers this plugin contributes.
     * Called after initialize() returns successfully.
     */
    List<CapabilityProvider> capabilityProviders();

    /**
     * Returns planner providers this plugin contributes.
     * Plugin planners are tried before the built-in rule-based planner.
     * Higher-priority planners (PlannerDescriptor.priority) are tried first.
     */
    default List<PlannerProvider> plannerProviders() {
        return List.of();
    }

    /**
     * Called before the plugin ClassLoader is closed.
     * Implementations must release resources, flush buffers, and close connections.
     * Must not throw — log and suppress any errors.
     */
    void shutdown();
}
