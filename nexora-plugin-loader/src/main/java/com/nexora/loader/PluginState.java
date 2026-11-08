package com.nexora.loader;

import com.nexora.spi.Capability;
import com.nexora.spi.CapabilityDescriptor;
import com.nexora.spi.NexoraPlugin;
import com.nexora.spi.Planner;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One loaded version of a plugin. A reload creates a new state for the same plugin id; the old one
 * stays reachable from in-flight invocations through its {@link #leases()} until they finish.
 */
final class PluginState {

    /** A capability as handed to the registry, so it can be removed or restored exactly. */
    record CapabilityRegistration(CapabilityDescriptor descriptor, Capability capability) {
        CapabilityRegistration {
            Objects.requireNonNull(descriptor, "descriptor must not be null");
            Objects.requireNonNull(capability, "capability must not be null");
        }
    }

    private final NexoraPlugin plugin;
    private final URLClassLoader classLoader;
    private final Path jar;
    private final InvocationLeases leases;
    private final AtomicReference<PluginLifecycle> lifecycle;
    private volatile List<CapabilityRegistration> capabilities = List.of();
    private volatile List<Planner> planners = List.of();

    /**
     * @param classLoader null for a plugin registered in code, which shares the engine's class loader
     * @param jar         null for a plugin registered in code
     * @param leases      null for a plugin registered in code; its code is never released
     */
    PluginState(
            NexoraPlugin plugin,
            URLClassLoader classLoader,
            Path jar,
            InvocationLeases leases,
            PluginLifecycle initial) {
        this.plugin = Objects.requireNonNull(plugin, "plugin must not be null");
        this.classLoader = classLoader;
        this.jar = jar;
        this.leases = leases;
        this.lifecycle = new AtomicReference<>(initial);
    }

    void transition(PluginLifecycle next) {
        lifecycle.set(next);
    }

    NexoraPlugin plugin() {
        return plugin;
    }

    String version() {
        return plugin.descriptor().version();
    }

    URLClassLoader classLoader() {
        return classLoader;
    }

    Path jar() {
        return jar;
    }

    /** Null for a plugin registered in code. */
    InvocationLeases leases() {
        return leases;
    }

    boolean fromJar() {
        return classLoader != null;
    }

    PluginLifecycle lifecycle() {
        return lifecycle.get();
    }

    List<CapabilityRegistration> capabilities() {
        return capabilities;
    }

    void capabilities(List<CapabilityRegistration> registered) {
        this.capabilities = List.copyOf(registered);
    }

    List<Planner> planners() {
        return planners;
    }

    void planners(List<Planner> registered) {
        this.planners = List.copyOf(registered);
    }
}
