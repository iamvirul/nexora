package com.nexora.api;

import com.nexora.core.execution.ExecutionResult;
import com.nexora.core.intent.Intent;
import com.nexora.event.ExecutionEventBus;
import com.nexora.event.EventHandler;
import com.nexora.event.ExecutionEvent;
import com.nexora.event.InProcessEventBus;
import com.nexora.event.Subscription;
import com.nexora.executor.CapabilityInvoker;
import com.nexora.executor.DagStepScheduler;
import com.nexora.executor.ExecutionInterceptor;
import com.nexora.executor.InterceptorPipeline;
import com.nexora.executor.interceptor.RetryInterceptor;
import com.nexora.executor.interceptor.TimeoutInterceptor;
import com.nexora.executor.interceptor.TracingInterceptor;
import com.nexora.loader.PluginManager;
import com.nexora.planner.engine.PlannerEngine;
import com.nexora.planner.model.StepDefinition;
import com.nexora.planner.registry.PlanRegistry;
import com.nexora.registry.DefaultCapabilityRegistry;
import com.nexora.retry.DefaultRetryPolicyRegistry;
import com.nexora.retry.RetryPolicy;
import com.nexora.retry.RetryPolicyRegistry;
import com.nexora.runtime.engine.ExecutionEngine;
import com.nexora.spi.CapabilityRegistry;
import com.nexora.spi.NexoraPlugin;
import com.nexora.tracing.NoopTracer;
import com.nexora.tracing.Tracer;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Public entry point for the Nexora execution platform.
 *
 * Construct via {@link #builder()} — the builder wires all internal components.
 * All fields are effectively final after build(); the engine is thread-safe.
 *
 * Example:
 * <pre>{@code
 * NexoraEngine engine = NexoraEngine.builder()
 *     .withPlugin(myPlugin)
 *     .withStepDefinition(new StepDefinition("validate", "validate_order", goal -> goal.contains("order")))
 *     .build();
 *
 * engine.execute(new Intent("process_order", Map.of("orderId", "123")))
 *       .thenAccept(result -> System.out.println(result.status()));
 * }</pre>
 */
public final class NexoraEngine {

    private final ExecutionEngine engine;
    private final PluginManager pluginManager;
    private final ExecutionEventBus eventBus;
    private final com.nexora.spi.CapabilityRegistry capabilityRegistry;

    private NexoraEngine(
            ExecutionEngine engine,
            PluginManager pluginManager,
            ExecutionEventBus eventBus,
            com.nexora.spi.CapabilityRegistry capabilityRegistry) {
        this.engine = engine;
        this.pluginManager = pluginManager;
        this.eventBus = eventBus;
        this.capabilityRegistry = capabilityRegistry;
    }

    public CompletableFuture<ExecutionResult> execute(Intent intent) {
        return engine.execute(intent);
    }

    /** Convenience overload for quick use without constructing an Intent manually. */
    public CompletableFuture<ExecutionResult> execute(String goal, Map<String, Object> context) {
        return engine.execute(new Intent(goal, context));
    }

    public <E extends ExecutionEvent> Subscription subscribe(Class<E> eventType, EventHandler<E> handler) {
        return eventBus.subscribe(eventType, handler);
    }

    /** Load and activate a plugin JAR at runtime. */
    public void loadPlugin(Path pluginJar, String pluginId) {
        pluginManager.loadPlugin(pluginJar);
        pluginManager.activatePlugin(pluginId);
    }

    public List<com.nexora.spi.CapabilityDescriptor> listCapabilities() {
        return capabilityRegistry.listAll();
    }

    public List<String> activePluginIds() {
        return pluginManager.activePluginIds();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private Executor executor = Executors.newVirtualThreadPerTaskExecutor();
        private Tracer tracer = NoopTracer.INSTANCE;
        private RetryPolicy defaultRetryPolicy = RetryPolicy.noRetry();
        private Duration defaultTimeout = null;

        private final List<NexoraPlugin> plugins = new ArrayList<>();
        private final List<Path> pluginJars = new ArrayList<>();
        private final List<StepDefinition> stepDefinitions = new ArrayList<>();

        public Builder withExecutor(Executor e) {
            this.executor = Objects.requireNonNull(e);
            return this;
        }

        public Builder withTracer(Tracer t) {
            this.tracer = Objects.requireNonNull(t);
            return this;
        }

        public Builder withDefaultRetryPolicy(RetryPolicy policy) {
            this.defaultRetryPolicy = Objects.requireNonNull(policy);
            return this;
        }

        public Builder withDefaultTimeout(Duration timeout) {
            this.defaultTimeout = timeout;
            return this;
        }

        /** Register a plugin instance directly (no JAR needed — useful for tests). */
        public Builder withPlugin(NexoraPlugin plugin) {
            plugins.add(Objects.requireNonNull(plugin));
            return this;
        }

        /** Load a plugin from a JAR file. */
        public Builder withPluginJar(Path jar) {
            pluginJars.add(Objects.requireNonNull(jar));
            return this;
        }

        public Builder withStepDefinition(StepDefinition def) {
            stepDefinitions.add(Objects.requireNonNull(def));
            return this;
        }

        public NexoraEngine build() {
            // Infrastructure
            CapabilityRegistry capabilityRegistry = new DefaultCapabilityRegistry();
            InProcessEventBus eventBus = new InProcessEventBus(executor);
            PluginManager pluginManager = new PluginManager(capabilityRegistry, eventBus);

            // Register inline plugins
            for (NexoraPlugin plugin : plugins) {
                pluginManager.registerPlugin(plugin);
                pluginManager.activatePlugin(plugin.descriptor().id());
            }

            // Load JAR-based plugins (id read from descriptor after loading)
            for (Path jar : pluginJars) {
                pluginManager.loadPlugin(jar);
            }

            // Retry policies
            RetryPolicyRegistry retryPolicyRegistry = new DefaultRetryPolicyRegistry();
            retryPolicyRegistry.setDefault(defaultRetryPolicy);

            // Interceptor pipeline
            List<ExecutionInterceptor> interceptors = List.of(
                    new TracingInterceptor(tracer),
                    new RetryInterceptor(retryPolicyRegistry),
                    new TimeoutInterceptor(executor, defaultTimeout)
            );
            InterceptorPipeline pipeline = new InterceptorPipeline(
                    interceptors,
                    new CapabilityInvoker(capabilityRegistry)
            );

            // DAG scheduler
            DagStepScheduler scheduler = new DagStepScheduler(pipeline, retryPolicyRegistry, eventBus, executor);

            // Planner
            PlanRegistry planRegistry = new PlanRegistry();
            stepDefinitions.forEach(planRegistry::register);
            PlannerEngine plannerEngine = new PlannerEngine(planRegistry);

            // Engine
            ExecutionEngine engine = new ExecutionEngine(plannerEngine, scheduler, eventBus);

            return new NexoraEngine(engine, pluginManager, eventBus, capabilityRegistry);
        }
    }
}
