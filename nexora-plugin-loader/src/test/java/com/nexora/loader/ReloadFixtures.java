package com.nexora.loader;

import com.nexora.core.capability.CapabilityResult;
import com.nexora.core.intent.Intent;
import com.nexora.core.plan.Plan;
import com.nexora.spi.Capability;
import com.nexora.spi.CapabilityDescriptor;
import com.nexora.spi.CapabilityProvider;
import com.nexora.spi.NexoraPlugin;
import com.nexora.spi.Planner;
import com.nexora.spi.PlannerDescriptor;
import com.nexora.spi.PlannerProvider;
import com.nexora.spi.PlanningContext;
import com.nexora.spi.PluginContext;
import com.nexora.spi.PluginDescriptor;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/**
 * Plugin versions for reload tests. Each is public with a no-arg constructor so ServiceLoader can
 * instantiate it from a jar's service descriptor; the classes themselves resolve from the test
 * class path, so their static coordination state is shared with the test.
 */
final class ReloadFixtures {

    static final String PLUGIN_ID = "reload-fixture";
    static final String ECHO = "fixture.echo";
    static final String LEGACY = "fixture.legacy";
    static final String ADDED = "fixture.added";

    /** Times a version's shutdown() ran, keyed by version. */
    static final Map<String, AtomicInteger> SHUTDOWNS = new ConcurrentHashMap<>();

    /** When set, version 1's echo blocks on it, standing in for a long-running invocation. */
    static volatile CountDownLatch v1Gate;

    static volatile CountDownLatch v1Entered;

    private ReloadFixtures() {}

    static void reset() {
        SHUTDOWNS.clear();
        v1Gate = null;
        v1Entered = null;
    }

    static int shutdowns(String version) {
        AtomicInteger count = SHUTDOWNS.get(version);
        return count == null ? 0 : count.get();
    }

    /** A jar holding only the service descriptor naming {@code pluginClass}. */
    static Path jarFor(Path dir, String name, Class<? extends NexoraPlugin> pluginClass) throws IOException {
        Path jar = dir.resolve(name);
        try (OutputStream out = Files.newOutputStream(jar);
                JarOutputStream jarOut = new JarOutputStream(out)) {
            jarOut.putNextEntry(new JarEntry("META-INF/services/" + NexoraPlugin.class.getName()));
            jarOut.write(pluginClass.getName().getBytes(StandardCharsets.UTF_8));
            jarOut.closeEntry();
        }
        return jar;
    }

    /** A jar with no service descriptor at all. */
    static Path emptyJar(Path dir, String name) throws IOException {
        Path jar = dir.resolve(name);
        try (OutputStream out = Files.newOutputStream(jar);
                JarOutputStream jarOut = new JarOutputStream(out)) {
            jarOut.putNextEntry(new JarEntry("README.txt"));
            jarOut.write("no plugin here".getBytes(StandardCharsets.UTF_8));
            jarOut.closeEntry();
        }
        return jar;
    }

    abstract static class Base implements NexoraPlugin {
        private final String id;
        private final String version;
        private final List<String> capabilityIds;
        private final List<String> required;

        Base(String id, String version, List<String> capabilityIds, List<String> required) {
            this.id = id;
            this.version = version;
            this.capabilityIds = capabilityIds;
            this.required = required;
        }

        @Override
        public PluginDescriptor descriptor() {
            return new PluginDescriptor(id, version, id, required, null);
        }

        @Override
        public void initialize(PluginContext context) {
            // nothing to acquire
        }

        @Override
        public List<CapabilityProvider> capabilityProviders() {
            return capabilityIds.stream().map(this::provider).toList();
        }

        @Override
        public List<PlannerProvider> plannerProviders() {
            String plannerId = "planner-" + version;
            return List.of(new PlannerProvider() {
                @Override
                public PlannerDescriptor descriptor() {
                    return new PlannerDescriptor(plannerId, "fixture planner", 10);
                }

                @Override
                public Planner create(PluginContext context) {
                    return new FixturePlanner(plannerId);
                }
            });
        }

        @Override
        public void shutdown() {
            SHUTDOWNS.computeIfAbsent(version, v -> new AtomicInteger()).incrementAndGet();
        }

        CapabilityResult echo() {
            return CapabilityResult.success(version);
        }

        private CapabilityProvider provider(String capabilityId) {
            return new CapabilityProvider() {
                @Override
                public CapabilityDescriptor descriptor() {
                    return new CapabilityDescriptor(capabilityId, "fixture", List.of(), List.of(), true, false);
                }

                @Override
                public Capability create(PluginContext context) {
                    return request -> echo();
                }
            };
        }
    }

    /** Version 1: echo and legacy. Echo blocks on {@link #v1Gate} when one is set. */
    public static final class V1 extends Base {
        public V1() {
            super(PLUGIN_ID, "1.0.0", List.of(ECHO, LEGACY), List.of());
        }

        @Override
        CapabilityResult echo() {
            CountDownLatch gate = v1Gate;
            if (gate != null) {
                v1Entered.countDown();
                try {
                    if (!gate.await(10, TimeUnit.SECONDS)) {
                        return CapabilityResult.failure("TEST_TIMEOUT", "gate never opened");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return CapabilityResult.failure("INTERRUPTED", "interrupted");
                }
            }
            return super.echo();
        }
    }

    /** Version 2: drops legacy, keeps echo, adds added. */
    public static final class V2 extends Base {
        public V2() {
            super(PLUGIN_ID, "2.0.0", List.of(ECHO, ADDED), List.of());
        }
    }

    /** Version 3: same capabilities as version 2. */
    public static final class V3 extends Base {
        public V3() {
            super(PLUGIN_ID, "3.0.0", List.of(ECHO, ADDED), List.of());
        }
    }

    /** Declares a different plugin id than the one being reloaded. */
    public static final class OtherId extends Base {
        public OtherId() {
            super("someone-else", "9.0.0", List.of(ECHO), List.of());
        }
    }

    /** Throws from initialize(). */
    public static final class BrokenInit extends Base {
        public BrokenInit() {
            super(PLUGIN_ID, "4.0.0-broken", List.of(ECHO), List.of());
        }

        @Override
        public void initialize(PluginContext context) {
            throw new IllegalStateException("database unreachable");
        }
    }

    /** Requires a plugin that is never active. */
    public static final class MissingDependency extends Base {
        public MissingDependency() {
            super(PLUGIN_ID, "5.0.0", List.of(ECHO), List.of("absent-plugin"));
        }
    }

    /** Provides a capability id another plugin already owns. */
    public static final class Conflicting extends Base {
        static final String TAKEN = "other.capability";

        public Conflicting() {
            super(PLUGIN_ID, "6.0.0", List.of(ECHO, TAKEN), List.of());
        }
    }

    static final class FixturePlanner implements Planner {
        private final String id;

        FixturePlanner(String id) {
            this.id = id;
        }

        @Override
        public PlannerDescriptor descriptor() {
            return new PlannerDescriptor(id, "fixture planner", 10);
        }

        @Override
        public boolean canPlan(Intent intent, PlanningContext context) {
            return false;
        }

        @Override
        public Plan plan(Intent intent, PlanningContext context) {
            throw new UnsupportedOperationException("fixture planner never plans");
        }
    }
}
