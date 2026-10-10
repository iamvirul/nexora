package com.nexora.api;

import com.nexora.core.capability.CapabilityResult;
import com.nexora.core.execution.ExecutionResult;
import com.nexora.core.execution.ExecutionStatus;
import com.nexora.core.intent.Intent;
import com.nexora.planner.model.StepDefinition;
import com.nexora.spi.Capability;
import com.nexora.spi.CapabilityDescriptor;
import com.nexora.spi.CapabilityProvider;
import com.nexora.spi.NexoraPlugin;
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
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regression tests for #140: plugins added via {@link NexoraEngine.Builder#withPluginJar(Path)}
 * must be activated by {@code build()}, not just loaded.
 */
class PluginJarActivationTest {

    private static final String SERVICE_FILE = "META-INF/services/" + NexoraPlugin.class.getName();

    @TempDir
    Path tempDir;

    @Test
    void buildActivatesPluginLoadedFromJar() throws Exception {
        Path jar = pluginJar("echo.jar", EchoJarPlugin.class);

        try (NexoraEngine engine = NexoraEngine.builder().withPluginJar(jar).build()) {
            assertThat(engine.activePluginIds()).containsExactly(EchoJarPlugin.PLUGIN_ID);
            assertThat(engine.listCapabilities())
                    .extracting(CapabilityDescriptor::id)
                    .containsExactly(EchoJarPlugin.CAPABILITY_ID);
            assertThat(engine.readiness().checks())
                    .containsEntry(NexoraEngine.ReadinessReport.CHECK_PLUGINS, NexoraEngine.HealthStatus.UP);
        }
    }

    @Test
    void jarPluginCapabilityServesExecutions() throws Exception {
        Path jar = pluginJar("echo.jar", EchoJarPlugin.class);

        try (NexoraEngine engine = NexoraEngine.builder()
                .withPluginJar(jar)
                .withStepDefinition(StepDefinition.builder("echo_step", EchoJarPlugin.CAPABILITY_ID)
                        .withMatcher(goal -> goal.contains("echo"))
                        .build())
                .build()) {

            ExecutionResult result = engine.execute(new Intent("echo hello", Map.of()))
                    .get(10, TimeUnit.SECONDS);

            assertThat(result.status()).isEqualTo(ExecutionStatus.COMPLETED);
        }
    }

    @Test
    void buildFailsWhenJarPluginInitializationFails() throws Exception {
        Path jar = pluginJar("broken.jar", FailingJarPlugin.class);
        NexoraEngine.Builder builder = NexoraEngine.builder().withPluginJar(jar);

        assertThatThrownBy(builder::build)
                .isInstanceOf(PluginInitializationException.class)
                .hasMessageContaining(FailingJarPlugin.PLUGIN_ID);
    }

    @Test
    void jarPluginCanDependOnInlinePlugin() throws Exception {
        Path jar = pluginJar("dependent.jar", DependentJarPlugin.class);

        try (NexoraEngine engine = NexoraEngine.builder()
                .withPlugin(new EchoJarPlugin())
                .withPluginJar(jar)
                .build()) {
            assertThat(engine.activePluginIds())
                    .containsExactlyInAnyOrder(EchoJarPlugin.PLUGIN_ID, DependentJarPlugin.PLUGIN_ID);
        }
    }

    /**
     * Builds a real plugin jar containing only the ServiceLoader descriptor. The plugin class
     * itself is on the test classpath and resolves through the plugin class loader's parent
     * fallback, so ServiceLoader discovery and the load path run exactly as in production.
     */
    private Path pluginJar(String fileName, Class<? extends NexoraPlugin> pluginClass) throws IOException {
        Path jar = tempDir.resolve(fileName);
        try (OutputStream out = Files.newOutputStream(jar);
             JarOutputStream jarOut = new JarOutputStream(out)) {
            jarOut.putNextEntry(new JarEntry(SERVICE_FILE));
            jarOut.write(pluginClass.getName().getBytes(StandardCharsets.UTF_8));
            jarOut.closeEntry();
        }
        return jar;
    }

    public static final class EchoJarPlugin implements NexoraPlugin {
        static final String PLUGIN_ID = "jar-echo-plugin";
        static final String CAPABILITY_ID = "jar_echo";

        @Override
        public PluginDescriptor descriptor() {
            return new PluginDescriptor(PLUGIN_ID, "1.0.0", "Echo plugin loaded from a jar", List.of(), null);
        }

        @Override
        public void initialize(PluginContext context) {
            // no state to set up
        }

        @Override
        public List<CapabilityProvider> capabilityProviders() {
            return List.of(new CapabilityProvider() {
                @Override
                public CapabilityDescriptor descriptor() {
                    return new CapabilityDescriptor(CAPABILITY_ID, "Echo", List.of(), List.of(), true, false);
                }

                @Override
                public Capability create(PluginContext context) {
                    return request -> CapabilityResult.success("echoed");
                }
            });
        }

        @Override
        public void shutdown() {
            // nothing to release
        }
    }

    public static final class FailingJarPlugin implements NexoraPlugin {
        static final String PLUGIN_ID = "jar-failing-plugin";

        @Override
        public PluginDescriptor descriptor() {
            return new PluginDescriptor(PLUGIN_ID, "1.0.0", "Plugin whose initialize throws", List.of(), null);
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
        public void shutdown() {
            // nothing to release
        }
    }

    public static final class DependentJarPlugin implements NexoraPlugin {
        static final String PLUGIN_ID = "jar-dependent-plugin";

        @Override
        public PluginDescriptor descriptor() {
            return new PluginDescriptor(PLUGIN_ID, "1.0.0", "Requires the echo plugin",
                    List.of(EchoJarPlugin.PLUGIN_ID), null);
        }

        @Override
        public void initialize(PluginContext context) {
            // no state to set up
        }

        @Override
        public List<CapabilityProvider> capabilityProviders() {
            return List.of();
        }

        @Override
        public void shutdown() {
            // nothing to release
        }
    }
}
