package com.nexora.planner.llm;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.nexora.api.NexoraEngine;
import com.nexora.core.capability.CapabilityResult;
import com.nexora.core.execution.ExecutionResult;
import com.nexora.core.execution.ExecutionStatus;
import com.nexora.core.execution.StepResult;
import com.nexora.core.intent.Intent;
import com.nexora.planner.model.StepDefinition;
import com.nexora.spi.Capability;
import com.nexora.spi.CapabilityDescriptor;
import com.nexora.spi.CapabilityProvider;
import com.nexora.spi.NexoraPlugin;
import com.nexora.spi.PluginContext;
import com.nexora.spi.PluginDescriptor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/**
 * Loads the packaged plugin JAR ({@code -plugin.jar}) into a real engine, the way an operator
 * would, with the Anthropic SDK pointed at a local fake API. Proves the JAR is self-contained, that
 * a valid model answer runs as the plan, that an invalid one falls back to the rule planner, and
 * that a repeated goal is served from the cache.
 */
@Tag("integration")
class LlmPlannerPluginJarIT {

    private static final String PLUGIN_JAR_PROPERTY = "nexora.test.pluginJar";
    private static final List<String> CONFIG_PROPERTIES =
            List.of("nexora.llm.provider", "nexora.llm.baseUrl", "nexora.llm.apiKeyFile", "nexora.llm.maxRetries");

    @TempDir
    Path dir;

    private FakeLlmServer server;
    private NexoraEngine engine;
    private final List<String> invoked = new CopyOnWriteArrayList<>();
    private final ListAppender<ILoggingEvent> plannerLog = new ListAppender<>();
    private final Logger plannerLogger = (Logger) LoggerFactory.getLogger(LlmPlanner.class);

    @BeforeEach
    void setUp() throws IOException {
        server = new FakeLlmServer();
        System.setProperty("nexora.llm.provider", "anthropic");
        System.setProperty("nexora.llm.baseUrl", server.baseUrl());
        System.setProperty(
                "nexora.llm.apiKeyFile",
                Files.writeString(dir.resolve("key"), "test-key").toString());
        System.setProperty("nexora.llm.maxRetries", "0");
        plannerLog.start();
        plannerLogger.addAppender(plannerLog);
        engine = NexoraEngine.builder()
                .withPlugin(new OrderCapabilities())
                .withPluginJar(pluginJar())
                // The rule-based plan the engine falls back to: validate only.
                .withStepDefinition(StepDefinition.builder("validate", "validate_order")
                        .withMatcher(goal -> goal.contains("order"))
                        .build())
                .build();
    }

    @AfterEach
    void tearDown() {
        plannerLogger.detachAppender(plannerLog);
        if (engine != null) {
            engine.close();
        }
        server.close();
        CONFIG_PROPERTIES.forEach(System::clearProperty);
    }

    @Test
    void pluginJarLoadsAndActivates() {
        assertThat(engine.activePluginIds()).contains(LlmPlannerPlugin.PLUGIN_ID);
        assertThat(engine.readiness().ready()).isTrue();
    }

    @Test
    void validModelAnswerRunsAsThePlan() throws Exception {
        server.anthropicAnswer(Fixtures.VALID_PLAN, "end_turn");

        ExecutionResult result = execute();

        assertThat(result.status()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(result.stepResults())
                .extracting(StepResult::stepId)
                .containsExactlyInAnyOrder("validate", "charge", "receipt");
        // Context bindings resolve: every step received the request's orderId.
        assertThat(invoked).containsExactly("validate_order:ORD-7", "charge_card:ORD-7", "send_receipt:ORD-7");
        assertThat(server.lastRequest()
                        .body()
                        .path("output_config")
                        .path("format")
                        .path("type")
                        .asText())
                .isEqualTo("json_schema");
    }

    @Test
    void invalidModelAnswerFallsBackToTheRulePlanner() throws Exception {
        server.anthropicAnswer(
                "{\"steps\": [{\"id\": \"x\", \"capabilityId\": \"launch_rocket\", \"dependsOn\": [], \"inputs\": []}]}",
                "end_turn");

        ExecutionResult result = execute();

        assertThat(result.status()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(result.stepResults()).extracting(StepResult::stepId).containsExactly("validate");
        assertThat(plannerLog.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("reason=INVALID_PLAN");
        });
    }

    @Test
    void unreachableModelFallsBackToTheRulePlanner() throws Exception {
        server.reply(500, FakeLlmServer.anthropicError("api_error", "boom"));

        ExecutionResult result = execute();

        assertThat(result.status()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(result.stepResults()).extracting(StepResult::stepId).containsExactly("validate");
    }

    @Test
    void repeatedGoalIsPlannedFromTheCache() throws Exception {
        server.anthropicAnswer(Fixtures.VALID_PLAN, "end_turn");

        execute();
        ExecutionResult second = execute();

        assertThat(second.stepResults()).hasSize(3);
        assertThat(server.requests()).hasSize(1);
    }

    private ExecutionResult execute() throws Exception {
        return engine.execute(new Intent(Fixtures.GOAL, Map.of("orderId", "ORD-7")))
                .get(30, TimeUnit.SECONDS);
    }

    private static Path pluginJar() {
        String path = System.getProperty(PLUGIN_JAR_PROPERTY);
        assertThat(path).as("failsafe sets " + PLUGIN_JAR_PROPERTY).isNotBlank();
        Path jar = Path.of(path);
        assertThat(jar)
                .as("run `mvn verify` so the plugin JAR is packaged first")
                .exists();
        return jar;
    }

    /** The order capabilities from {@link Fixtures}, each recording that it ran. */
    private final class OrderCapabilities implements NexoraPlugin {
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
            return Fixtures.CAPABILITIES.stream().map(this::provider).toList();
        }

        private CapabilityProvider provider(CapabilityDescriptor descriptor) {
            return new CapabilityProvider() {
                @Override
                public CapabilityDescriptor descriptor() {
                    return descriptor;
                }

                @Override
                public Capability create(PluginContext context) {
                    return request -> {
                        invoked.add(descriptor.id() + ":" + request.inputs().get("orderId"));
                        return CapabilityResult.success(Map.of("id", descriptor.id() + "-1"));
                    };
                }
            };
        }

        @Override
        public void shutdown() {
            // nothing to release
        }
    }
}
