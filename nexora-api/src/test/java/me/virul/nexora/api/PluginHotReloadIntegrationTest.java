package me.virul.nexora.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import me.virul.nexora.core.capability.CapabilityResult;
import me.virul.nexora.core.execution.ExecutionResult;
import me.virul.nexora.core.execution.ExecutionStatus;
import me.virul.nexora.core.intent.Intent;
import me.virul.nexora.core.plan.Plan;
import me.virul.nexora.core.plan.Step;
import me.virul.nexora.event.ExecutionEvent;
import me.virul.nexora.event.PluginReloadFailedEvent;
import me.virul.nexora.event.PluginReloadedEvent;
import me.virul.nexora.loader.PluginReloadResult;
import me.virul.nexora.persistence.jdbc.JdbcExecutionStore;
import me.virul.nexora.planner.model.StepDefinition;
import me.virul.nexora.spi.Capability;
import me.virul.nexora.spi.CapabilityDescriptor;
import me.virul.nexora.spi.CapabilityProvider;
import me.virul.nexora.spi.NexoraPlugin;
import me.virul.nexora.spi.Planner;
import me.virul.nexora.spi.PlannerDescriptor;
import me.virul.nexora.spi.PlannerProvider;
import me.virul.nexora.spi.PlanningContext;
import me.virul.nexora.spi.PluginContext;
import me.virul.nexora.spi.PluginDescriptor;
import me.virul.nexora.spi.PluginInitializationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Issue #43: swapping a plugin JAR on a running engine. */
class PluginHotReloadIntegrationTest {

    private static final String SERVICE_FILE = "META-INF/services/" + NexoraPlugin.class.getName();
    private static final String PLUGIN_ID = "greeter";
    private static final String CAPABILITY_ID = "greet";
    private static final String V2_ONLY_GOAL = "planned by v2";

    /** When set, version 1's capability blocks on it, standing in for a slow step. */
    static volatile CountDownLatch v1Gate;

    static volatile CountDownLatch v1Entered;

    @TempDir
    Path tempDir;

    private final List<ExecutionEvent> events = new CopyOnWriteArrayList<>();
    private NexoraEngine engine;

    @BeforeEach
    void setUp() throws IOException {
        v1Gate = null;
        v1Entered = null;
        engine = NexoraEngine.builder()
                .withExecutionStore(JdbcExecutionStore.h2InMemory())
                .withPluginJar(pluginJar("greeter-1.jar", GreeterV1.class))
                .withStepDefinition(StepDefinition.builder("greet_step", CAPABILITY_ID)
                        .withMatcher(goal -> goal.startsWith("greet"))
                        .build())
                .build();
        engine.subscribeSync(PluginReloadedEvent.class, events::add);
        engine.subscribeSync(PluginReloadFailedEvent.class, events::add);
    }

    @AfterEach
    void tearDown() {
        CountDownLatch gate = v1Gate;
        if (gate != null) {
            gate.countDown();
        }
        engine.close();
    }

    @Test
    void reloadWithoutExecutionStoreIsRejected() throws IOException {
        Path v2 = pluginJar("greeter-2.jar", GreeterV2.class);
        try (NexoraEngine stateless = NexoraEngine.builder()
                .withPluginJar(pluginJar("stateless-1.jar", GreeterV1.class))
                .build()) {

            assertThatThrownBy(() -> stateless.reloadPlugin(PLUGIN_ID, v2))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("ExecutionStore");
            assertThat(stateless.activePluginIds()).containsExactly(PLUGIN_ID);
        }
    }

    @Test
    void inFlightExecutionFinishesOnOldVersionWhileNewOnesUseNewVersion() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        v1Entered = new CountDownLatch(1);
        v1Gate = gate;
        CompletableFuture<ExecutionResult> inFlight = engine.execute(new Intent("greet slow", Map.of()));
        assertThat(v1Entered.await(5, TimeUnit.SECONDS)).isTrue();

        PluginReloadResult result = engine.reloadPlugin(PLUGIN_ID, pluginJar("greeter-2.jar", GreeterV2.class));

        assertThat(result.previousVersion()).isEqualTo("1.0.0");
        assertThat(result.newVersion()).isEqualTo("2.0.0");
        ExecutionResult fresh =
                engine.execute(new Intent("greet fast", Map.of())).get(10, TimeUnit.SECONDS);
        assertThat(outputOf(fresh)).isEqualTo("hello from 2.0.0");
        assertThat(inFlight).isNotDone();

        gate.countDown();

        ExecutionResult finished = inFlight.get(10, TimeUnit.SECONDS);
        assertThat(finished.status()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(outputOf(finished)).isEqualTo("hello from 1.0.0");
    }

    @Test
    void reloadPublishesEventWithOldAndNewVersion() throws IOException {
        engine.reloadPlugin(PLUGIN_ID, pluginJar("greeter-2.jar", GreeterV2.class));

        assertThat(events).singleElement().isInstanceOfSatisfying(PluginReloadedEvent.class, event -> {
            assertThat(event.pluginId()).isEqualTo(PLUGIN_ID);
            assertThat(event.previousVersion()).isEqualTo("1.0.0");
            assertThat(event.newVersion()).isEqualTo("2.0.0");
        });
    }

    @Test
    void reloadedPluginPlannerPlansNewIntents() throws Exception {
        engine.reloadPlugin(PLUGIN_ID, pluginJar("greeter-2.jar", GreeterV2.class));

        ExecutionResult result =
                engine.execute(new Intent(V2_ONLY_GOAL, Map.of())).get(10, TimeUnit.SECONDS);

        assertThat(result.status()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(outputOf(result)).isEqualTo("hello from 2.0.0");
    }

    @Test
    void failedReloadKeepsOldVersionServing() throws Exception {
        Path broken = pluginJar("greeter-broken.jar", BrokenGreeter.class);

        assertThatThrownBy(() -> engine.reloadPlugin(PLUGIN_ID, broken))
                .isInstanceOf(PluginInitializationException.class)
                .hasMessageContaining("1.0.0 is still active");

        ExecutionResult result =
                engine.execute(new Intent("greet again", Map.of())).get(10, TimeUnit.SECONDS);
        assertThat(outputOf(result)).isEqualTo("hello from 1.0.0");
        assertThat(engine.readiness().ready()).isTrue();
        assertThat(events).singleElement().isInstanceOfSatisfying(PluginReloadFailedEvent.class, event -> {
            assertThat(event.activeVersion()).isEqualTo("1.0.0");
            assertThat(event.reason()).contains("config missing");
        });
    }

    @Test
    void blankPluginIdIsRejected() throws IOException {
        Path v2 = pluginJar("greeter-2.jar", GreeterV2.class);

        assertThatThrownBy(() -> engine.reloadPlugin(" ", v2)).isInstanceOf(IllegalArgumentException.class);
    }

    private static Object outputOf(ExecutionResult result) {
        return result.stepResults().get(0).capabilityResult().output();
    }

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

    abstract static class Greeter implements NexoraPlugin {
        private final String version;

        Greeter(String version) {
            this.version = version;
        }

        @Override
        public PluginDescriptor descriptor() {
            return new PluginDescriptor(PLUGIN_ID, version, "Greets", List.of(), null);
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
                    return new CapabilityDescriptor(CAPABILITY_ID, "Greet", List.of(), List.of(), true, false);
                }

                @Override
                public Capability create(PluginContext context) {
                    return request -> greet();
                }
            });
        }

        @Override
        public void shutdown() {
            // nothing to release
        }

        CapabilityResult greet() {
            return CapabilityResult.success("hello from " + version);
        }
    }

    public static final class GreeterV1 extends Greeter {
        public GreeterV1() {
            super("1.0.0");
        }

        @Override
        CapabilityResult greet() {
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
            return super.greet();
        }
    }

    /** Adds a planner that plans {@value #V2_ONLY_GOAL}, which no step definition matches. */
    public static final class GreeterV2 extends Greeter {
        public GreeterV2() {
            super("2.0.0");
        }

        @Override
        public List<PlannerProvider> plannerProviders() {
            return List.of(new PlannerProvider() {
                @Override
                public PlannerDescriptor descriptor() {
                    return new PlannerDescriptor("greeter-planner", "Plans the v2 goal", 100);
                }

                @Override
                public Planner create(PluginContext context) {
                    return new Planner() {
                        @Override
                        public PlannerDescriptor descriptor() {
                            return new PlannerDescriptor("greeter-planner", "Plans the v2 goal", 100);
                        }

                        @Override
                        public boolean canPlan(Intent intent, PlanningContext ctx) {
                            return V2_ONLY_GOAL.equals(intent.getGoal());
                        }

                        @Override
                        public Plan plan(Intent intent, PlanningContext ctx) {
                            return new Plan(List.of(Step.of("greet_step", CAPABILITY_ID)));
                        }
                    };
                }
            });
        }
    }

    public static final class BrokenGreeter extends Greeter {
        public BrokenGreeter() {
            super("3.0.0");
        }

        @Override
        public void initialize(PluginContext context) {
            throw new IllegalStateException("config missing");
        }
    }
}
