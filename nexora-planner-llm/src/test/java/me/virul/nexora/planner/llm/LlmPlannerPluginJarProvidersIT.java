package me.virul.nexora.planner.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import me.virul.nexora.api.NexoraEngine;
import me.virul.nexora.core.capability.CapabilityResult;
import me.virul.nexora.core.execution.ExecutionResult;
import me.virul.nexora.core.execution.ExecutionStatus;
import me.virul.nexora.core.execution.StepResult;
import me.virul.nexora.core.intent.Intent;
import me.virul.nexora.spi.Capability;
import me.virul.nexora.spi.CapabilityDescriptor;
import me.virul.nexora.spi.CapabilityProvider;
import me.virul.nexora.spi.NexoraPlugin;
import me.virul.nexora.spi.PluginContext;
import me.virul.nexora.spi.PluginDescriptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The packaged plugin JAR on each non-Anthropic SDK path (OpenAI for OpenRouter, Google Gen AI for
 * Gemini) against the local fake API. Guards the bundled SDKs the way {@link LlmPlannerPluginJarIT}
 * guards Anthropic's: a class the shaded JAR lost would fail here, not in production.
 */
@Tag("integration")
class LlmPlannerPluginJarProvidersIT {

    private static final List<String> CONFIG_PROPERTIES = List.of(
            "nexora.llm.provider",
            "nexora.llm.baseUrl",
            "nexora.llm.apiKeyFile",
            "nexora.llm.model",
            "nexora.llm.maxRetries");

    @TempDir
    Path dir;

    private FakeLlmServer server;
    private NexoraEngine engine;

    @BeforeEach
    void startServer() throws IOException {
        server = new FakeLlmServer();
    }

    @AfterEach
    void tearDown() {
        if (engine != null) {
            engine.close();
        }
        server.close();
        CONFIG_PROPERTIES.forEach(System::clearProperty);
    }

    @ParameterizedTest
    @EnumSource(
            value = LlmProvider.class,
            names = {"OPENROUTER", "GEMINI"})
    void validModelAnswerRunsAsThePlan(LlmProvider provider) throws Exception {
        boolean gemini = provider == LlmProvider.GEMINI;
        if (gemini) {
            server.geminiAnswer(Fixtures.VALID_PLAN, "STOP");
        } else {
            server.openAiAnswer(Fixtures.VALID_PLAN, "stop", null);
        }
        engine = engineFor(provider, gemini ? server.baseUrl() : server.openRouterBaseUrl(), "test-model");

        ExecutionResult result = engine.execute(new Intent(Fixtures.GOAL, Map.of("orderId", "ORD-7")))
                .get(30, TimeUnit.SECONDS);

        assertThat(result.status()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(result.stepResults())
                .extracting(StepResult::stepId)
                .containsExactlyInAnyOrder("validate", "charge", "receipt");
        if (gemini) {
            assertThat(server.lastRequest().path()).endsWith("/models/test-model:generateContent");
            assertThat(server.lastRequest()
                            .body()
                            .path("generationConfig")
                            .path("responseMimeType")
                            .asText())
                    .isEqualTo("application/json");
        } else {
            assertThat(server.lastRequest().path()).isEqualTo(FakeLlmServer.OPENROUTER_PATH);
            assertThat(server.lastRequest()
                            .body()
                            .path("response_format")
                            .path("type")
                            .asText())
                    .isEqualTo("json_schema");
        }
    }

    private NexoraEngine engineFor(LlmProvider provider, String baseUrl, String model) throws IOException {
        System.setProperty("nexora.llm.provider", provider.name().toLowerCase(java.util.Locale.ROOT));
        System.setProperty("nexora.llm.baseUrl", baseUrl);
        System.setProperty("nexora.llm.model", model);
        System.setProperty(
                "nexora.llm.apiKeyFile",
                Files.writeString(dir.resolve("key"), "test-key").toString());
        System.setProperty("nexora.llm.maxRetries", "0");
        return NexoraEngine.builder()
                .withPlugin(new OrderCapabilities())
                .withPluginJar(Path.of(System.getProperty("nexora.test.pluginJar")))
                .build();
    }

    private static final class OrderCapabilities implements NexoraPlugin {
        @Override
        public PluginDescriptor descriptor() {
            return new PluginDescriptor("order-capabilities", "1.0.0", "Order workflow", List.of(), null);
        }

        @Override
        public void initialize(PluginContext context) {
            // nothing to set up
        }

        @Override
        public List<CapabilityProvider> capabilityProviders() {
            return Fixtures.CAPABILITIES.stream()
                    .map(descriptor -> (CapabilityProvider) new CapabilityProvider() {
                        @Override
                        public CapabilityDescriptor descriptor() {
                            return descriptor;
                        }

                        @Override
                        public Capability create(PluginContext context) {
                            return request -> CapabilityResult.success(Map.of("id", descriptor.id() + "-1"));
                        }
                    })
                    .toList();
        }

        @Override
        public void shutdown() {
            // nothing to release
        }
    }
}
