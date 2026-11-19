package me.virul.nexora.benchmarks;

import java.util.List;
import java.util.Map;
import me.virul.nexora.core.capability.CapabilityResult;
import me.virul.nexora.spi.Capability;
import me.virul.nexora.spi.CapabilityDescriptor;
import me.virul.nexora.spi.CapabilityProvider;
import me.virul.nexora.spi.NexoraPlugin;
import me.virul.nexora.spi.PluginContext;
import me.virul.nexora.spi.PluginDescriptor;

/**
 * The plugin {@link PluginLoadBenchmark} packs into a JAR. Public with a no-arg constructor so
 * {@link java.util.ServiceLoader} can create it from the JAR's service descriptor. It depends on
 * nothing but the SPI, because the plugin class loader defines it and its nested classes from the
 * JAR, apart from the rest of this module.
 */
public final class JarPlugin implements NexoraPlugin {

    /** The id this plugin declares. */
    public static final String PLUGIN_ID = "bench-jar";

    /** The capability this plugin contributes. */
    public static final String CAPABILITY_ID = "bench.jar.noop";

    private static final PluginDescriptor DESCRIPTOR =
            new PluginDescriptor(PLUGIN_ID, "1.0.0", "Plugin load benchmark", List.of(), null);

    /** Creates the plugin; called by {@link java.util.ServiceLoader}. */
    public JarPlugin() {}

    @Override
    public PluginDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public void initialize(PluginContext context) {}

    @Override
    public List<CapabilityProvider> capabilityProviders() {
        CapabilityDescriptor descriptor =
                new CapabilityDescriptor(CAPABILITY_ID, "No-op", List.of(), List.of(), true, false);
        return List.of(new CapabilityProvider() {
            @Override
            public CapabilityDescriptor descriptor() {
                return descriptor;
            }

            @Override
            public Capability create(PluginContext context) {
                CapabilityResult ok = CapabilityResult.success(Map.of("ok", true));
                return request -> ok;
            }
        });
    }

    @Override
    public void shutdown() {}
}
