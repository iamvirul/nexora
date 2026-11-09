package com.nexora.planner.llm;

import com.nexora.spi.CapabilityProvider;
import com.nexora.spi.NexoraPlugin;
import com.nexora.spi.Planner;
import com.nexora.spi.PlannerDescriptor;
import com.nexora.spi.PlannerProvider;
import com.nexora.spi.PluginContext;
import com.nexora.spi.PluginDescriptor;
import com.nexora.spi.PluginInitializationException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Plugin that contributes {@link LlmPlanner}. Load its JAR like any plugin
 * ({@code withPluginJar}, or {@code plugins} in {@code nexora.json}) and configure it through
 * {@code NEXORA_LLM_*} environment variables or {@code nexora.llm.*} system properties; see
 * {@link LlmPlannerConfig}.
 *
 * <p>With no provider chosen and no provider API key set, the plugin activates without a planner
 * and logs a {@code WARN}, so the engine keeps planning with its rules. A setting that is present
 * but invalid fails activation.
 */
public final class LlmPlannerPlugin implements NexoraPlugin {

    /** The id the plugin registers under, as {@code activePluginIds()} lists it. */
    public static final String PLUGIN_ID = "nexora-planner-llm";

    private static final String UNKNOWN_VERSION = "0.0.0-dev";

    private final Supplier<Optional<LlmPlannerConfig>> configSource;
    private final Function<LlmPlannerConfig, PlanCompletionClient> clientFactory;
    private volatile LlmPlanner planner;
    private volatile PlanCompletionClient client;

    /** Used by {@link java.util.ServiceLoader}: reads the environment and calls the real provider. */
    public LlmPlannerPlugin() {
        this(LlmPlannerConfig::fromEnvironment, LlmPlannerPlugin::clientFor);
    }

    LlmPlannerPlugin(
            Supplier<Optional<LlmPlannerConfig>> configSource,
            Function<LlmPlannerConfig, PlanCompletionClient> clientFactory) {
        this.configSource = Objects.requireNonNull(configSource, "configSource must not be null");
        this.clientFactory = Objects.requireNonNull(clientFactory, "clientFactory must not be null");
    }

    /**
     * The SDK client for {@code config}'s provider. For using the planner as a library, for example
     * to preview a plan before running it: {@code new LlmPlanner(clientFor(config), config).plan(...)}.
     * The caller owns the returned client and must close it.
     */
    public static PlanCompletionClient clientFor(LlmPlannerConfig config) {
        return switch (config.provider()) {
            case ANTHROPIC -> new AnthropicPlanClient(config);
            case OPENAI, OPENROUTER -> new OpenAiPlanClient(config);
            case GEMINI -> new GeminiPlanClient(config);
        };
    }

    @Override
    public PluginDescriptor descriptor() {
        String version = LlmPlannerPlugin.class.getPackage().getImplementationVersion();
        return new PluginDescriptor(
                PLUGIN_ID,
                version == null ? UNKNOWN_VERSION : version,
                "Plans goals with an LLM (Anthropic, OpenAI, OpenRouter or Gemini)",
                List.of(),
                null);
    }

    @Override
    public void initialize(PluginContext context) {
        Optional<LlmPlannerConfig> config;
        try {
            config = configSource.get();
        } catch (IllegalArgumentException e) {
            throw new PluginInitializationException("Invalid LLM planner configuration: " + e.getMessage(), e);
        }
        if (config.isEmpty()) {
            context.logger()
                    .warn(
                            "LLM planner is off: set NEXORA_LLM_PROVIDER and the provider's API key"
                                    + " (ANTHROPIC_API_KEY, OPENAI_API_KEY, OPENROUTER_API_KEY or GEMINI_API_KEY) to enable it");
            return;
        }
        PlanCompletionClient created = clientFactory.apply(config.get());
        client = created;
        planner = new LlmPlanner(created, config.get());
        context.logger().info("LLM planner ready config={}", config.get());
    }

    @Override
    public List<CapabilityProvider> capabilityProviders() {
        return List.of();
    }

    @Override
    public List<PlannerProvider> plannerProviders() {
        LlmPlanner ready = planner;
        if (ready == null) {
            return List.of();
        }
        return List.of(new PlannerProvider() {
            @Override
            public PlannerDescriptor descriptor() {
                return ready.descriptor();
            }

            @Override
            public Planner create(PluginContext context) {
                return ready;
            }
        });
    }

    /** The active planner, for metrics and tests; empty when the plugin is off. */
    public Optional<LlmPlanner> planner() {
        return Optional.ofNullable(planner);
    }

    @Override
    public void shutdown() {
        PlanCompletionClient active = client;
        if (active != null) {
            active.close();
        }
    }
}
