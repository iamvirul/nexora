package me.virul.nexora.spi;

import java.util.List;

/**
 * Entry point every plugin JAR must implement exactly once.
 * Discovered via {@link java.util.ServiceLoader} from META-INF/services/me.virul.nexora.spi.NexoraPlugin.
 *
 * <p>Lifecycle: LOADED, then {@link #initialize(PluginContext)}, then ACTIVE, then
 * {@link #shutdown()}, then INACTIVE.
 */
public interface NexoraPlugin {

    /**
     * Identifies the plugin and the plugins it depends on. Read before {@link #initialize(PluginContext)}.
     *
     * @return the descriptor; never null
     */
    PluginDescriptor descriptor();

    /**
     * Called once after the plugin ClassLoader is initialised.
     * Implementations should register capabilities, load configuration,
     * and acquire any external resources here.
     *
     * @param context the services the engine provides to this plugin
     * @throws PluginInitializationException if the plugin cannot start cleanly.
     */
    void initialize(PluginContext context) throws PluginInitializationException;

    /**
     * Returns all capability providers this plugin contributes.
     * Called after initialize() returns successfully.
     *
     * @return the providers; empty when the plugin contributes none
     */
    List<CapabilityProvider> capabilityProviders();

    /**
     * Returns planner providers this plugin contributes.
     * Plugin planners are tried before the built-in rule-based planner.
     * Higher-priority planners ({@link PlannerDescriptor#priority()}) are tried first.
     *
     * @return the providers; empty by default
     */
    default List<PlannerProvider> plannerProviders() {
        return List.of();
    }

    /**
     * Called before the plugin ClassLoader is closed.
     * Implementations must release resources, flush buffers, and close connections.
     * Must not throw: log and suppress any errors.
     */
    void shutdown();
}
