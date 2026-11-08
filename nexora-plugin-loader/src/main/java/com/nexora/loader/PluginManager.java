package com.nexora.loader;

import com.nexora.event.ExecutionEventBus;
import com.nexora.event.PluginActivatedEvent;
import com.nexora.event.PluginDeactivatedEvent;
import com.nexora.event.PluginReloadFailedEvent;
import com.nexora.event.PluginReloadedEvent;
import com.nexora.loader.PluginState.CapabilityRegistration;
import com.nexora.spi.Capability;
import com.nexora.spi.CapabilityProvider;
import com.nexora.spi.CapabilityRegistry;
import com.nexora.spi.NexoraPlugin;
import com.nexora.spi.Planner;
import com.nexora.spi.PlannerProvider;
import com.nexora.spi.PluginInitializationException;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages the full lifecycle of plugins: load → activate → reload → deactivate → unload.
 *
 * Each JAR plugin gets its own {@link PluginClassLoader} for class isolation.
 * Dependency ordering is enforced: a plugin's requiredPlugins must be ACTIVE
 * before the plugin itself can be activated.
 *
 * <p>Every capability and planner from a JAR plugin is registered behind a lease on the plugin
 * version that provides it. When a version is deactivated or replaced by {@link #reloadPlugin}, its
 * {@code shutdown()} runs and its class loader closes only after the invocations still running on
 * it have finished.
 *
 * <p>Lifecycle changes (load, register, activate, reload, deactivate) are serialised; reads are not.
 */
public final class PluginManager {

    private static final Logger log = LoggerFactory.getLogger(PluginManager.class);

    private final CapabilityRegistry capabilityRegistry;
    private final ExecutionEventBus eventBus;
    private final ConcurrentHashMap<String, PluginState> plugins = new ConcurrentHashMap<>();
    private final ReentrantLock lifecycleLock = new ReentrantLock();
    /** Immutable snapshot, replaced whole on every change so readers never see a half-swapped set. */
    private volatile List<Planner> registeredPlanners = List.of();

    public PluginManager(CapabilityRegistry capabilityRegistry, ExecutionEventBus eventBus) {
        this.capabilityRegistry = Objects.requireNonNull(capabilityRegistry);
        this.eventBus = Objects.requireNonNull(eventBus);
    }

    /**
     * Loads a plugin JAR and transitions it to LOADED state.
     * Does not initialize — call activatePlugin() with the returned id after loading.
     *
     * @return the plugin id declared by the JAR's {@link NexoraPlugin#descriptor()}
     */
    public String loadPlugin(Path pluginJar) {
        Objects.requireNonNull(pluginJar, "pluginJar must not be null");
        log.info("Loading plugin from jar={}", pluginJar);

        lifecycleLock.lock();
        try {
            PluginState state = openJar(pluginJar, PluginLifecycle.LOADED);
            String pluginId = state.plugin().descriptor().id();
            if (plugins.putIfAbsent(pluginId, state) != null) {
                closeQuietly(pluginId, state.classLoader());
                throw new PluginInitializationException("Plugin already loaded: " + pluginId);
            }
            log.info("Plugin loaded id={} version={}", pluginId, state.version());
            return pluginId;
        } finally {
            lifecycleLock.unlock();
        }
    }

    /**
     * Registers a programmatically provided plugin (no JAR needed).
     * Useful for testing and for built-in engine capabilities.
     */
    public void registerPlugin(NexoraPlugin plugin) {
        Objects.requireNonNull(plugin, "plugin must not be null");
        String pluginId = plugin.descriptor().id();
        // No custom ClassLoader for inline plugins — they share the engine's ClassLoader
        PluginState state = new PluginState(plugin, null, null, null, PluginLifecycle.LOADED);
        lifecycleLock.lock();
        try {
            if (plugins.putIfAbsent(pluginId, state) != null) {
                throw new PluginInitializationException("Plugin already registered: " + pluginId);
            }
        } finally {
            lifecycleLock.unlock();
        }
    }

    /**
     * Initialises the plugin and makes its capabilities available.
     * Required plugins must already be ACTIVE.
     */
    public void activatePlugin(String pluginId) {
        lifecycleLock.lock();
        try {
            PluginState state = requirePlugin(pluginId);
            if (state.lifecycle() == PluginLifecycle.ACTIVE) return;

            validateRequiredPlugins(state.plugin());

            state.transition(PluginLifecycle.INITIALIZING);
            log.info("Activating plugin id={}", pluginId);
            try {
                initializeContributions(state);
            } catch (RuntimeException e) {
                state.transition(PluginLifecycle.FAILED);
                throw e;
            }

            for (CapabilityRegistration registration : state.capabilities()) {
                capabilityRegistry.register(registration.descriptor(), registration.capability());
            }
            swapPlanners(List.of(), state.planners());

            state.transition(PluginLifecycle.ACTIVE);
            eventBus.publish(new PluginActivatedEvent(pluginId, state.version(), Instant.now()));
            log.info("Plugin active id={}", pluginId);
        } finally {
            lifecycleLock.unlock();
        }
    }

    /**
     * Replaces an ACTIVE JAR plugin with the version in {@code newJar} without stopping the engine.
     *
     * <p>The new version is loaded under a fresh {@link PluginClassLoader} and fully initialised
     * before anything is swapped, so the old version keeps serving while that happens; for a moment
     * both are initialised side by side. Once ready, its capabilities and planners replace the old
     * ones and every new invocation uses them. Invocations already running on the old version finish
     * on it; its {@code shutdown()} runs and its class loader closes when the last one returns.
     * Capabilities the new version no longer provides are removed.
     *
     * <p>If the JAR cannot be loaded, declares another plugin id, needs a plugin that is not ACTIVE,
     * fails to initialise, or provides a capability another plugin owns, nothing is swapped: the old
     * version stays active, a {@link PluginReloadFailedEvent} is published, and
     * {@link PluginInitializationException} is thrown. On success a {@link PluginReloadedEvent} is
     * published.
     *
     * <p>Overwrite a JAR in place only if the old version has no classes left to load from it;
     * writing the new version to a new path is always safe.
     *
     * @throws IllegalStateException          if the plugin is not loaded, not ACTIVE, or was registered in code
     * @throws PluginInitializationException  if the new version could not replace the old one
     */
    public PluginReloadResult reloadPlugin(String pluginId, Path newJar) {
        Objects.requireNonNull(pluginId, "pluginId must not be null");
        Objects.requireNonNull(newJar, "newJar must not be null");
        lifecycleLock.lock();
        try {
            PluginState current = requireReloadable(pluginId);
            log.info(
                    "Reloading plugin id={} activeVersion={} activeJar={} newJar={}",
                    pluginId,
                    current.version(),
                    current.jar(),
                    newJar);

            PluginState next;
            try {
                next = prepareReplacement(current, newJar);
                swapIn(current, next);
            } catch (RuntimeException e) {
                throw reloadFailed(current, newJar, e);
            }

            current.transition(PluginLifecycle.INACTIVE);
            log.info(
                    "Plugin reloaded id={} previousVersion={} newVersion={} previousVersionInFlight={}",
                    pluginId,
                    current.version(),
                    next.version(),
                    current.leases().inFlight());
            current.leases().retire();
            eventBus.publish(new PluginReloadedEvent(pluginId, current.version(), next.version(), Instant.now()));
            return new PluginReloadResult(pluginId, current.version(), next.version());
        } finally {
            lifecycleLock.unlock();
        }
    }

    /**
     * Shuts down the plugin and removes all its capabilities from the registry.
     * Refuses if another ACTIVE plugin declares a dependency on this one.
     *
     * <p>For a JAR plugin, {@code shutdown()} and closing the class loader wait for invocations
     * already running on it to finish; new invocations no longer find its capabilities.
     */
    public void deactivatePlugin(String pluginId) {
        lifecycleLock.lock();
        try {
            PluginState state = requirePlugin(pluginId);
            if (state.lifecycle() != PluginLifecycle.ACTIVE) return;

            checkNoDependents(pluginId);
            state.transition(PluginLifecycle.DEACTIVATING);
            log.info("Deactivating plugin id={}", pluginId);

            for (CapabilityRegistration registration : state.capabilities()) {
                capabilityRegistry.deregister(registration.descriptor().id());
            }
            swapPlanners(state.planners(), List.of());

            if (state.fromJar()) {
                log.info(
                        "Releasing plugin version id={} version={} inFlight={}",
                        pluginId,
                        state.version(),
                        state.leases().inFlight());
                state.leases().retire();
            } else {
                shutdownQuietly(pluginId, state.plugin());
            }

            state.transition(PluginLifecycle.INACTIVE);
            plugins.remove(pluginId);
            eventBus.publish(new PluginDeactivatedEvent(pluginId, Instant.now()));
            log.info("Plugin deactivated id={}", pluginId);
        } finally {
            lifecycleLock.unlock();
        }
    }

    public PluginLifecycle getLifecycle(String pluginId) {
        PluginState state = plugins.get(pluginId);
        return state != null ? state.lifecycle() : PluginLifecycle.UNLOADED;
    }

    /** The version of a loaded plugin, as its descriptor declares it; empty when not loaded. */
    public Optional<String> version(String pluginId) {
        return Optional.ofNullable(plugins.get(pluginId)).map(PluginState::version);
    }

    /** The JAR a loaded plugin's current version came from; empty when not loaded or registered in code. */
    public Optional<Path> jar(String pluginId) {
        return Optional.ofNullable(plugins.get(pluginId)).map(PluginState::jar);
    }

    /**
     * The planners of every ACTIVE plugin. The returned list is an immutable snapshot; the same
     * instance is returned until a plugin is activated, reloaded or deactivated.
     */
    public List<Planner> registeredPlanners() {
        return registeredPlanners;
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

    /**
     * Registered plugins that are not serving capabilities: loaded but never activated,
     * mid-activation, or FAILED. Used for readiness checks — any entry here means the engine is not
     * fully ready.
     */
    public List<String> nonActivePluginIds() {
        List<String> result = new ArrayList<>();
        for (Map.Entry<String, PluginState> entry : plugins.entrySet()) {
            if (entry.getValue().lifecycle() != PluginLifecycle.ACTIVE) {
                result.add(entry.getKey());
            }
        }
        return Collections.unmodifiableList(result);
    }

    private PluginState openJar(Path pluginJar, PluginLifecycle initial) {
        if (!Files.isRegularFile(pluginJar) || !Files.isReadable(pluginJar)) {
            throw new PluginInitializationException("Plugin jar is not a readable file: " + pluginJar);
        }
        URL jarUrl;
        try {
            jarUrl = pluginJar.toUri().toURL();
        } catch (MalformedURLException | IllegalArgumentException e) {
            throw new PluginInitializationException("Cannot resolve URL for plugin jar: " + pluginJar, e);
        }

        PluginClassLoader classLoader =
                new PluginClassLoader(new URL[] {jarUrl}, getClass().getClassLoader());
        try {
            NexoraPlugin plugin = ServiceLoader.load(NexoraPlugin.class, classLoader)
                    .findFirst()
                    .orElseThrow(() -> new PluginInitializationException("No NexoraPlugin implementation found in jar: "
                            + pluginJar + ". Ensure META-INF/services/com.nexora.spi.NexoraPlugin is present."));
            String pluginId = plugin.descriptor().id();
            InvocationLeases leases = new InvocationLeases(() -> releaseVersion(pluginId, plugin, classLoader));
            return new PluginState(plugin, classLoader, pluginJar, leases, initial);
        } catch (ServiceConfigurationError e) {
            closeQuietly(pluginJar.toString(), classLoader);
            throw new PluginInitializationException("Cannot instantiate the NexoraPlugin in jar: " + pluginJar, e);
        } catch (RuntimeException e) {
            closeQuietly(pluginJar.toString(), classLoader);
            throw e;
        }
    }

    /**
     * Calls {@code initialize()} and creates the plugin's capabilities and planners into
     * {@code state}, without registering anything. Throws {@link PluginInitializationException}
     * on any failure; the caller decides what to clean up.
     */
    private void initializeContributions(PluginState state) {
        String pluginId = state.plugin().descriptor().id();
        DefaultPluginContext ctx = new DefaultPluginContext(pluginId, Map.of(), capabilityRegistry);
        try {
            state.plugin().initialize(ctx);
        } catch (Exception e) {
            throw new PluginInitializationException("Plugin initialization failed: " + pluginId, e);
        }

        try {
            List<CapabilityRegistration> capabilities = new ArrayList<>();
            for (CapabilityProvider provider : state.plugin().capabilityProviders()) {
                String capabilityId = provider.descriptor().id();
                Capability instance = Objects.requireNonNull(
                        provider.create(ctx), () -> "Capability provider returned null for id=" + capabilityId);
                Capability registered = state.fromJar()
                        ? new LeasedCapability(capabilityId, instance, state.leases(), capabilityRegistry)
                        : instance;
                capabilities.add(new CapabilityRegistration(provider.descriptor(), registered));
                log.info("Created capability id={} from plugin={}", capabilityId, pluginId);
            }

            List<Planner> planners = new ArrayList<>();
            for (PlannerProvider provider : state.plugin().plannerProviders()) {
                Planner planner = Objects.requireNonNull(
                        provider.create(ctx),
                        () -> "Planner provider returned null for id="
                                + provider.descriptor().id());
                planners.add(state.fromJar() ? new LeasedPlanner(planner, state.leases()) : planner);
                log.info(
                        "Created planner id={} from plugin={}",
                        provider.descriptor().id(),
                        pluginId);
            }
            state.capabilities(capabilities);
            state.planners(planners);
        } catch (RuntimeException e) {
            throw new PluginInitializationException(
                    "Plugin " + pluginId + " failed to create its capabilities or planners", e);
        }
    }

    private PluginState requireReloadable(String pluginId) {
        PluginState current = requirePlugin(pluginId);
        if (current.lifecycle() != PluginLifecycle.ACTIVE) {
            throw new IllegalStateException(
                    "Plugin '" + pluginId + "' must be ACTIVE to reload, but its state is " + current.lifecycle());
        }
        if (!current.fromJar()) {
            throw new IllegalStateException("Plugin '" + pluginId
                    + "' was registered in code, not loaded from a JAR; only JAR plugins can be hot reloaded");
        }
        return current;
    }

    /** Loads and initialises the new version without touching the registry. Cleans up after itself on failure. */
    private PluginState prepareReplacement(PluginState current, Path newJar) {
        String pluginId = current.plugin().descriptor().id();
        PluginState next = openJar(newJar, PluginLifecycle.LOADED);
        boolean initialized = false;
        try {
            String declaredId = next.plugin().descriptor().id();
            if (!pluginId.equals(declaredId)) {
                throw new PluginInitializationException(
                        "Jar " + newJar + " declares plugin id '" + declaredId + "', expected '" + pluginId + "'");
            }
            validateRequiredPlugins(next.plugin());
            next.transition(PluginLifecycle.INITIALIZING);
            // From here shutdown() runs on failure, so a half-initialised version releases what it acquired.
            initialized = true;
            initializeContributions(next);
            requireNoForeignCapabilities(current, next);
            return next;
        } catch (RuntimeException e) {
            discard(next, initialized);
            throw e;
        }
    }

    /** A capability id another plugin owns would be overwritten by the swap and lost on rollback. */
    private void requireNoForeignCapabilities(PluginState current, PluginState next) {
        Set<String> owned = capabilityIds(current.capabilities());
        for (CapabilityRegistration registration : next.capabilities()) {
            String id = registration.descriptor().id();
            if (!owned.contains(id) && capabilityRegistry.findDescriptor(id).isPresent()) {
                throw new PluginInitializationException(
                        "New version provides capability '" + id + "', which is already registered by another plugin");
            }
        }
    }

    /** Points the registry and planner set at {@code next}. Restores {@code current} if anything throws. */
    private void swapIn(PluginState current, PluginState next) {
        String pluginId = current.plugin().descriptor().id();
        Set<String> oldIds = capabilityIds(current.capabilities());
        Set<String> newIds = capabilityIds(next.capabilities());
        try {
            for (CapabilityRegistration registration : next.capabilities()) {
                capabilityRegistry.register(registration.descriptor(), registration.capability());
            }
            for (String removed : oldIds) {
                if (!newIds.contains(removed)) {
                    capabilityRegistry.deregister(removed);
                }
            }
            swapPlanners(current.planners(), next.planners());
            next.transition(PluginLifecycle.ACTIVE);
            plugins.put(pluginId, next);
        } catch (RuntimeException e) {
            log.error(
                    "Plugin swap failed, restoring previous version id={} version={}", pluginId, current.version(), e);
            for (String added : newIds) {
                if (!oldIds.contains(added)) {
                    capabilityRegistry.deregister(added);
                }
            }
            for (CapabilityRegistration registration : current.capabilities()) {
                capabilityRegistry.register(registration.descriptor(), registration.capability());
            }
            swapPlanners(next.planners(), current.planners());
            plugins.put(pluginId, current);
            discard(next, true);
            throw new PluginInitializationException("Could not swap in the new version of plugin " + pluginId, e);
        }
    }

    private PluginInitializationException reloadFailed(PluginState current, Path newJar, RuntimeException cause) {
        String pluginId = current.plugin().descriptor().id();
        String reason = describe(cause);
        log.warn(
                "Plugin reload failed, previous version still active id={} activeVersion={} newJar={} reason={}",
                pluginId,
                current.version(),
                newJar,
                reason,
                cause);
        eventBus.publish(new PluginReloadFailedEvent(pluginId, current.version(), reason, Instant.now()));
        return new PluginInitializationException(
                "Reload of plugin '" + pluginId + "' failed; version " + current.version() + " is still active: "
                        + reason,
                cause);
    }

    /** The exception message with its first cause, which usually holds the plugin's own explanation. */
    private static String describe(Throwable error) {
        String message = error.getMessage() != null
                ? error.getMessage()
                : error.getClass().getSimpleName();
        Throwable cause = error.getCause();
        if (cause == null || cause == error) {
            return message;
        }
        String causeMessage = cause.getMessage() != null
                ? cause.getMessage()
                : cause.getClass().getSimpleName();
        return message + ": " + causeMessage;
    }

    private void discard(PluginState candidate, boolean initialized) {
        String pluginId = candidate.plugin().descriptor().id();
        candidate.transition(PluginLifecycle.FAILED);
        if (initialized) {
            shutdownQuietly(pluginId, candidate.plugin());
        }
        closeQuietly(pluginId, candidate.classLoader());
    }

    /** Replaces {@code removed} by {@code added} in one write, so planning never sees neither or both. */
    private void swapPlanners(List<Planner> removed, List<Planner> added) {
        List<Planner> next = new ArrayList<>(registeredPlanners.size() + added.size());
        for (Planner planner : registeredPlanners) {
            if (!containsInstance(removed, planner)) {
                next.add(planner);
            }
        }
        next.addAll(added);
        registeredPlanners = List.copyOf(next);
    }

    private static boolean containsInstance(List<Planner> planners, Planner candidate) {
        for (Planner planner : planners) {
            if (planner == candidate) return true;
        }
        return false;
    }

    private static Set<String> capabilityIds(List<CapabilityRegistration> registrations) {
        Set<String> ids = new HashSet<>();
        for (CapabilityRegistration registration : registrations) {
            ids.add(registration.descriptor().id());
        }
        return ids;
    }

    /** Runs once per JAR plugin version, after it was retired and its last invocation returned. */
    private static void releaseVersion(String pluginId, NexoraPlugin plugin, PluginClassLoader classLoader) {
        shutdownQuietly(pluginId, plugin);
        closeQuietly(pluginId, classLoader);
        log.info(
                "Plugin version released id={} version={}",
                pluginId,
                plugin.descriptor().version());
    }

    private static void shutdownQuietly(String pluginId, NexoraPlugin plugin) {
        try {
            plugin.shutdown();
        } catch (Exception e) {
            log.warn("Plugin shutdown threw (suppressed). id={}", pluginId, e);
        }
    }

    private static void closeQuietly(String pluginId, URLClassLoader classLoader) {
        if (classLoader == null) return;
        try {
            classLoader.close();
        } catch (IOException e) {
            log.warn("Failed to close ClassLoader for plugin id={}", pluginId, e);
        }
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
                        "Plugin '" + plugin.descriptor().id() + "' requires plugin '" + required
                                + "' to be ACTIVE first, but its state is: "
                                + (dep == null ? "UNLOADED" : dep.lifecycle()));
            }
        }
    }

    private void checkNoDependents(String pluginId) {
        for (Map.Entry<String, PluginState> entry : plugins.entrySet()) {
            if (entry.getValue().lifecycle() == PluginLifecycle.ACTIVE
                    && entry.getValue().plugin().descriptor().requiredPlugins().contains(pluginId)) {
                throw new IllegalStateException("Cannot deactivate plugin '" + pluginId + "' — active plugin '"
                        + entry.getKey() + "' depends on it.");
            }
        }
    }
}
