package com.nexora.loader;

import com.nexora.event.ExecutionEventBus;
import com.nexora.event.PluginActivatedEvent;
import com.nexora.event.PluginDeactivatedEvent;
import com.nexora.spi.Capability;
import com.nexora.spi.CapabilityProvider;
import com.nexora.spi.CapabilityRegistry;
import com.nexora.spi.NexoraPlugin;
import com.nexora.spi.Planner;
import com.nexora.spi.PlannerProvider;
import com.nexora.spi.PluginInitializationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages the full lifecycle of plugins: load → activate → deactivate → unload.
 *
 * Each plugin gets its own {@link PluginClassLoader} for class isolation.
 * Dependency ordering is enforced: a plugin's requiredPlugins must be ACTIVE
 * before the plugin itself can be activated.
 */
public final class PluginManager {

    private static final Logger log = LoggerFactory.getLogger(PluginManager.class);

    private final CapabilityRegistry capabilityRegistry;
    private final ExecutionEventBus eventBus;
    private final ConcurrentHashMap<String, PluginState> plugins = new ConcurrentHashMap<>();
    private final List<Planner> registeredPlanners = Collections.synchronizedList(new ArrayList<>());

    public PluginManager(CapabilityRegistry capabilityRegistry, ExecutionEventBus eventBus) {
        this.capabilityRegistry = Objects.requireNonNull(capabilityRegistry);
        this.eventBus = Objects.requireNonNull(eventBus);
    }

    /**
     * Loads a plugin JAR and transitions it to LOADED state.
     * Does not initialize — call activatePlugin() after loading.
     */
    public void loadPlugin(Path pluginJar) {
        Objects.requireNonNull(pluginJar, "pluginJar must not be null");
        log.info("Loading plugin from jar={}", pluginJar);

        URL jarUrl;
        try {
            jarUrl = pluginJar.toUri().toURL();
        } catch (Exception e) {
            throw new PluginInitializationException("Cannot resolve URL for plugin jar: " + pluginJar, e);
        }

        PluginClassLoader classLoader = new PluginClassLoader(
                new URL[]{jarUrl},
                getClass().getClassLoader()
        );

        NexoraPlugin plugin = ServiceLoader
                .load(NexoraPlugin.class, classLoader)
                .findFirst()
                .orElseThrow(() -> new PluginInitializationException(
                        "No NexoraPlugin implementation found in jar: " + pluginJar +
                        ". Ensure META-INF/services/com.nexora.spi.NexoraPlugin is present."
                ));

        String pluginId = plugin.descriptor().id();
        if (plugins.containsKey(pluginId)) {
            throw new PluginInitializationException("Plugin already loaded: " + pluginId);
        }

        PluginState state = new PluginState(plugin, classLoader, PluginLifecycle.LOADED);
        plugins.put(pluginId, state);
        log.info("Plugin loaded id={} version={}", pluginId, plugin.descriptor().version());
    }

    /**
     * Registers a programmatically provided plugin (no JAR needed).
     * Useful for testing and for built-in engine capabilities.
     */
    public void registerPlugin(NexoraPlugin plugin) {
        Objects.requireNonNull(plugin, "plugin must not be null");
        String pluginId = plugin.descriptor().id();
        if (plugins.containsKey(pluginId)) {
            throw new PluginInitializationException("Plugin already registered: " + pluginId);
        }
        // No custom ClassLoader for inline plugins — they share the engine's ClassLoader
        PluginState state = new PluginState(plugin, null, PluginLifecycle.LOADED);
        plugins.put(pluginId, state);
    }

    /**
     * Initialises the plugin and makes its capabilities available.
     * Required plugins must already be ACTIVE.
     */
    public void activatePlugin(String pluginId) {
        PluginState state = requirePlugin(pluginId);
        if (state.lifecycle() == PluginLifecycle.ACTIVE) return;

        validateRequiredPlugins(state.plugin());

        state.transition(PluginLifecycle.INITIALIZING);
        log.info("Activating plugin id={}", pluginId);

        DefaultPluginContext ctx = new DefaultPluginContext(pluginId, Map.of(), capabilityRegistry);
        try {
            state.plugin().initialize(ctx);
        } catch (Exception e) {
            state.transition(PluginLifecycle.FAILED);
            throw new PluginInitializationException("Plugin initialization failed: " + pluginId, e);
        }

        for (CapabilityProvider provider : state.plugin().capabilityProviders()) {
            Capability instance = provider.create(ctx);
            capabilityRegistry.register(provider.descriptor(), instance);
            log.info("Registered capability id={} from plugin={}", provider.descriptor().id(), pluginId);
        }

        for (PlannerProvider provider : state.plugin().plannerProviders()) {
            Planner planner = provider.create(ctx);
            registeredPlanners.add(planner);
            log.info("Registered planner id={} from plugin={}", provider.descriptor().id(), pluginId);
        }

        state.transition(PluginLifecycle.ACTIVE);
        eventBus.publish(new PluginActivatedEvent(pluginId, state.plugin().descriptor().version(), Instant.now()));
        log.info("Plugin active id={}", pluginId);
    }

    /**
     * Shuts down the plugin and removes all its capabilities from the registry.
     * Refuses if another ACTIVE plugin declares a dependency on this one.
     */
    public void deactivatePlugin(String pluginId) {
        PluginState state = requirePlugin(pluginId);
        if (state.lifecycle() != PluginLifecycle.ACTIVE) return;

        checkNoDependents(pluginId);
        state.transition(PluginLifecycle.DEACTIVATING);
        log.info("Deactivating plugin id={}", pluginId);

        for (CapabilityProvider provider : state.plugin().capabilityProviders()) {
            capabilityRegistry.deregister(provider.descriptor().id());
        }

        try {
            state.plugin().shutdown();
        } catch (Exception e) {
            log.warn("Plugin shutdown threw (suppressed). id={}", pluginId, e);
        }

        if (state.classLoader() != null) {
            try {
                state.classLoader().close();
            } catch (IOException e) {
                log.warn("Failed to close ClassLoader for plugin id={}", pluginId, e);
            }
        }

        state.transition(PluginLifecycle.INACTIVE);
        plugins.remove(pluginId);
        eventBus.publish(new PluginDeactivatedEvent(pluginId, Instant.now()));
        log.info("Plugin deactivated id={}", pluginId);
    }

    public PluginLifecycle getLifecycle(String pluginId) {
        PluginState state = plugins.get(pluginId);
        return state != null ? state.lifecycle() : PluginLifecycle.UNLOADED;
    }

    public List<Planner> registeredPlanners() {
        return Collections.unmodifiableList(registeredPlanners);
    }

    public List<String> activePluginIds() {
        List<String> result = new ArrayList<>();
        for (Map.Entry<String, PluginState> entry : plugins.entrySet()) {
            if (entry.getValue().lifecycle() == PluginLifecycle.ACTIVE) {
                result.add(entry.getKey());
            }
        }
        return Collections.unmodifiableList(result);
    }

    private PluginState requirePlugin(String pluginId) {
        PluginState state = plugins.get(pluginId);
        if (state == null) {
            throw new IllegalStateException("Plugin not loaded: " + pluginId);
        }
        return state;
    }

    private void validateRequiredPlugins(NexoraPlugin plugin) {
        for (String required : plugin.descriptor().requiredPlugins()) {
            PluginState dep = plugins.get(required);
            if (dep == null || dep.lifecycle() != PluginLifecycle.ACTIVE) {
                throw new PluginInitializationException(
                        "Plugin '" + plugin.descriptor().id() + "' requires plugin '" + required +
                        "' to be ACTIVE first, but its state is: " +
                        (dep == null ? "UNLOADED" : dep.lifecycle())
                );
            }
        }
    }

    private void checkNoDependents(String pluginId) {
        for (Map.Entry<String, PluginState> entry : plugins.entrySet()) {
            if (entry.getValue().lifecycle() == PluginLifecycle.ACTIVE &&
                    entry.getValue().plugin().descriptor().requiredPlugins().contains(pluginId)) {
                throw new IllegalStateException(
                        "Cannot deactivate plugin '" + pluginId +
                        "' — active plugin '" + entry.getKey() + "' depends on it."
                );
            }
        }
    }
}
