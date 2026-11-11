package me.virul.nexora.spring.boot.autoconfigure;

import java.util.List;
import java.util.Map;
import me.virul.nexora.core.capability.CapabilityResult;
import me.virul.nexora.spi.Capability;
import me.virul.nexora.spi.CapabilityDescriptor;
import me.virul.nexora.spi.CapabilityProvider;
import me.virul.nexora.spi.NexoraPlugin;
import me.virul.nexora.spi.PluginContext;
import me.virul.nexora.spi.PluginDescriptor;

/** In-code plugin with one capability, {@value #CAPABILITY_ID}, that greets the context's {@code name}. */
final class GreetingPlugin implements NexoraPlugin {

    static final String PLUGIN_ID = "greeting-plugin";
    static final String CAPABILITY_ID = "greet";

    @Override
    public PluginDescriptor descriptor() {
        return new PluginDescriptor(PLUGIN_ID, "1.0.0", "Greets by name", List.of(), null);
    }

    @Override
    public void initialize(PluginContext context) {}

    @Override
    public List<CapabilityProvider> capabilityProviders() {
        return List.of(new CapabilityProvider() {
            @Override
            public CapabilityDescriptor descriptor() {
                return new CapabilityDescriptor(CAPABILITY_ID, "Greets by name", List.of(), List.of(), true, false);
            }

            @Override
            public Capability create(PluginContext context) {
                return request -> CapabilityResult.success(
                        Map.of("greeting", "hello " + request.inputs().getOrDefault("name", "world")));
            }
        });
    }

    @Override
    public void shutdown() {}
}
