package me.virul.nexora.benchmarks;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import me.virul.nexora.spi.Capability;
import me.virul.nexora.spi.CapabilityDescriptor;
import me.virul.nexora.spi.CapabilityProvider;
import me.virul.nexora.spi.NexoraPlugin;
import me.virul.nexora.spi.PluginContext;
import me.virul.nexora.spi.PluginDescriptor;

/**
 * An inline plugin that contributes the given capabilities as they are, so a benchmark measures the
 * engine around them rather than any capability work.
 */
public final class BenchmarkPlugin implements NexoraPlugin {

    private final PluginDescriptor descriptor;
    private final Map<String, Capability> capabilities;

    /**
     * Creates a plugin.
     *
     * @param pluginId     the plugin id
     * @param capabilities the capabilities to contribute, by capability id
     */
    public BenchmarkPlugin(String pluginId, Map<String, Capability> capabilities) {
        this.descriptor = new PluginDescriptor(pluginId, "1.0.0", "Benchmark capabilities", List.of(), null);
        this.capabilities = Map.copyOf(Objects.requireNonNull(capabilities, "capabilities"));
    }

    @Override
    public PluginDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public void initialize(PluginContext context) {}

    @Override
    public List<CapabilityProvider> capabilityProviders() {
        return capabilities.entrySet().stream()
                .map(entry -> provider(entry.getKey(), entry.getValue()))
                .toList();
    }

    @Override
    public void shutdown() {}

    private static CapabilityProvider provider(String capabilityId, Capability capability) {
        CapabilityDescriptor descriptor =
                new CapabilityDescriptor(capabilityId, "Benchmark capability", List.of(), List.of(), true, false);
        return new CapabilityProvider() {
            @Override
            public CapabilityDescriptor descriptor() {
                return descriptor;
            }

            @Override
            public Capability create(PluginContext context) {
                return capability;
            }
        };
    }
}
