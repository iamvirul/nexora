package com.nexora.loader;

import com.nexora.core.intent.Intent;
import com.nexora.core.plan.Plan;
import com.nexora.event.InProcessEventBus;
import com.nexora.registry.DefaultCapabilityRegistry;
import com.nexora.spi.CapabilityProvider;
import com.nexora.spi.NexoraPlugin;
import com.nexora.spi.Planner;
import com.nexora.spi.PlannerDescriptor;
import com.nexora.spi.PlannerProvider;
import com.nexora.spi.PlanningContext;
import com.nexora.spi.PluginContext;
import com.nexora.spi.PluginDescriptor;
import com.nexora.spi.PluginInitializationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PluginManagerTest {

    @Test
    void removesOnlyDeactivatedPluginPlanners() {
        PluginManager manager = new PluginManager(
                new DefaultCapabilityRegistry(),
                new InProcessEventBus(Runnable::run)
        );

        NexoraPlugin pluginA = new TestPlugin("plugin-a", "planner-a");
        NexoraPlugin pluginB = new TestPlugin("plugin-b", "planner-b");

        manager.registerPlugin(pluginA);
        manager.registerPlugin(pluginB);
        manager.activatePlugin("plugin-a");
        manager.activatePlugin("plugin-b");

        assertEquals(Set.of("planner-a", "planner-b"), plannerIds(manager));

        manager.deactivatePlugin("plugin-a");
        assertEquals(Set.of("planner-b"), plannerIds(manager));

        manager.deactivatePlugin("plugin-b");
        assertEquals(Set.of(), plannerIds(manager));
    }

    @Test
    void failsActivationWhenRequiredPluginIsNotActive() {
        PluginManager manager = new PluginManager(
                new DefaultCapabilityRegistry(),
                new InProcessEventBus(Runnable::run)
        );

        NexoraPlugin base = new TestPlugin("base-plugin", "base-planner");
        NexoraPlugin dependent = new TestPlugin("dependent-plugin", "dep-planner", List.of("base-plugin"));

        manager.registerPlugin(base);
        manager.registerPlugin(dependent);

        assertThrows(PluginInitializationException.class, () -> manager.activatePlugin("dependent-plugin"));
        assertEquals(PluginLifecycle.LOADED, manager.getLifecycle("dependent-plugin"));
    }

    @Test
    void preventsDeactivatingPluginWithActiveDependents() {
        PluginManager manager = new PluginManager(
                new DefaultCapabilityRegistry(),
                new InProcessEventBus(Runnable::run)
        );

        NexoraPlugin base = new TestPlugin("base-plugin", "base-planner");
        NexoraPlugin dependent = new TestPlugin("dependent-plugin", "dep-planner", List.of("base-plugin"));

        manager.registerPlugin(base);
        manager.registerPlugin(dependent);
        manager.activatePlugin("base-plugin");
        manager.activatePlugin("dependent-plugin");

        assertThrows(IllegalStateException.class, () -> manager.deactivatePlugin("base-plugin"));
        assertEquals(PluginLifecycle.ACTIVE, manager.getLifecycle("base-plugin"));
    }

    @Test
    void nonActivePluginIdsListsPluginsThatWereNeverActivated() {
        PluginManager manager = new PluginManager(
                new DefaultCapabilityRegistry(),
                new InProcessEventBus(Runnable::run)
        );

        manager.registerPlugin(new TestPlugin("plugin-a", "planner-a"));
        manager.registerPlugin(new TestPlugin("plugin-b", "planner-b"));
        manager.activatePlugin("plugin-a");

        assertEquals(List.of("plugin-b"), manager.nonActivePluginIds());
    }

    @Test
    void nonActivePluginIdsIsEmptyWhenAllPluginsAreActive() {
        PluginManager manager = new PluginManager(
                new DefaultCapabilityRegistry(),
                new InProcessEventBus(Runnable::run)
        );

        manager.registerPlugin(new TestPlugin("plugin-a", "planner-a"));
        manager.activatePlugin("plugin-a");

        assertEquals(List.of(), manager.nonActivePluginIds());
    }

    @Test
    void nonActivePluginIdsIncludesPluginWhoseInitializationFailed() {
        PluginManager manager = new PluginManager(
                new DefaultCapabilityRegistry(),
                new InProcessEventBus(Runnable::run)
        );

        manager.registerPlugin(new FailingPlugin("broken-plugin"));
        assertThrows(PluginInitializationException.class, () -> manager.activatePlugin("broken-plugin"));

        assertEquals(PluginLifecycle.FAILED, manager.getLifecycle("broken-plugin"));
        assertEquals(List.of("broken-plugin"), manager.nonActivePluginIds());
    }

    @Test
    void loadPluginReturnsIdDeclaredByJarAndLeavesPluginLoaded(@TempDir Path tempDir) throws IOException {
        PluginManager manager = new PluginManager(
                new DefaultCapabilityRegistry(),
                new InProcessEventBus(Runnable::run)
        );
        Path jar = tempDir.resolve("plugin.jar");
        try (OutputStream out = Files.newOutputStream(jar);
             JarOutputStream jarOut = new JarOutputStream(out)) {
            jarOut.putNextEntry(new JarEntry("META-INF/services/" + NexoraPlugin.class.getName()));
            jarOut.write(JarFixturePlugin.class.getName().getBytes(StandardCharsets.UTF_8));
            jarOut.closeEntry();
        }

        String pluginId = manager.loadPlugin(jar);

        assertEquals(JarFixturePlugin.PLUGIN_ID, pluginId);
        assertEquals(PluginLifecycle.LOADED, manager.getLifecycle(pluginId));
    }

    private static Set<String> plannerIds(PluginManager manager) {
        return manager.registeredPlanners().stream()
                .map(p -> p.descriptor().id())
                .collect(Collectors.toSet());
    }

    private static final class TestPlugin implements NexoraPlugin {
        private final String pluginId;
        private final String plannerId;
        private final List<String> requiredPlugins;

        private TestPlugin(String pluginId, String plannerId) {
            this(pluginId, plannerId, List.of());
        }

        private TestPlugin(String pluginId, String plannerId, List<String> requiredPlugins) {
            this.pluginId = pluginId;
            this.plannerId = plannerId;
            this.requiredPlugins = requiredPlugins;
        }

        @Override
        public PluginDescriptor descriptor() {
            return new PluginDescriptor(pluginId, "1.0.0", pluginId, requiredPlugins, null);
        }

        @Override
        public void initialize(PluginContext context) {
            // no-op
        }

        @Override
        public List<CapabilityProvider> capabilityProviders() {
            return List.of();
        }

        @Override
        public List<PlannerProvider> plannerProviders() {
            return List.of(new PlannerProvider() {
                @Override
                public PlannerDescriptor descriptor() {
                    return new PlannerDescriptor(plannerId, "test planner", 10);
                }

                @Override
                public Planner create(PluginContext context) {
                    return new TestPlanner(plannerId);
                }
            });
        }

        @Override
        public void shutdown() {
            // no-op
        }
    }

    /** Public with a no-arg constructor so ServiceLoader can instantiate it from a jar descriptor. */
    public static final class JarFixturePlugin implements NexoraPlugin {
        static final String PLUGIN_ID = "jar-fixture-plugin";

        @Override
        public PluginDescriptor descriptor() {
            return new PluginDescriptor(PLUGIN_ID, "1.0.0", PLUGIN_ID, List.of(), null);
        }

        @Override
        public void initialize(PluginContext context) {
            // no-op
        }

        @Override
        public List<CapabilityProvider> capabilityProviders() {
            return List.of();
        }

        @Override
        public List<PlannerProvider> plannerProviders() {
            return List.of();
        }

        @Override
        public void shutdown() {
            // no-op
        }
    }

    private static final class FailingPlugin implements NexoraPlugin {
        private final String pluginId;

        private FailingPlugin(String pluginId) {
            this.pluginId = pluginId;
        }

        @Override
        public PluginDescriptor descriptor() {
            return new PluginDescriptor(pluginId, "1.0.0", pluginId, List.of(), null);
        }

        @Override
        public void initialize(PluginContext context) {
            throw new IllegalStateException("simulated initialization failure");
        }

        @Override
        public List<CapabilityProvider> capabilityProviders() {
            return List.of();
        }

        @Override
        public List<PlannerProvider> plannerProviders() {
            return List.of();
        }

        @Override
        public void shutdown() {
            // no-op
        }
    }

    private static final class TestPlanner implements Planner {
        private final PlannerDescriptor descriptor;

        private TestPlanner(String plannerId) {
            this.descriptor = new PlannerDescriptor(plannerId, "test planner", 10);
        }

        @Override
        public PlannerDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public boolean canPlan(Intent intent, PlanningContext context) {
            return false;
        }

        @Override
        public Plan plan(Intent intent, PlanningContext context) {
            return new Plan(List.of());
        }
    }
}
