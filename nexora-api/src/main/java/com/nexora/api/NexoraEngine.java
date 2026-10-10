package com.nexora.api;

import com.nexora.core.context.TraceContext;
import com.nexora.core.execution.ExecutionHandle;
import com.nexora.core.execution.ExecutionResult;
import com.nexora.core.intent.Intent;
import com.nexora.event.ExecutionEventBus;
import com.nexora.event.EventHandler;
import com.nexora.event.ExecutionEvent;
import com.nexora.event.InProcessEventBus;
import com.nexora.event.Subscription;
import com.nexora.executor.CapabilityContractMonitor;
import com.nexora.executor.CapabilityInvoker;
import com.nexora.executor.DagStepScheduler;
import com.nexora.executor.ExecutionInterceptor;
import com.nexora.executor.InterceptorPipeline;
import com.nexora.executor.interceptor.RetryInterceptor;
import com.nexora.executor.interceptor.TimeoutInterceptor;
import com.nexora.executor.interceptor.TracingInterceptor;
import com.nexora.loader.PluginManager;
import com.nexora.persistence.ExecutionStore;
import com.nexora.planner.engine.CompositePlanner;
import com.nexora.saga.SagaOrchestrator;
import com.nexora.planner.engine.PlannerEngine;
import com.nexora.planner.engine.RulePlanner;
import com.nexora.planner.model.StepDefinition;
import com.nexora.planner.registry.PlanRegistry;
import com.nexora.registry.DefaultCapabilityRegistry;
import com.nexora.retry.DefaultRetryPolicyRegistry;
import com.nexora.retry.RetryPolicy;
import com.nexora.retry.RetryPolicyRegistry;
import com.nexora.runtime.engine.ExecutionEngine;
import com.nexora.spi.CapabilityRegistry;
import com.nexora.spi.NexoraPlugin;
import com.nexora.spi.Planner;
import com.nexora.tracing.NoopTracer;
import com.nexora.tracing.Tracer;

import com.nexora.persistence.MissedFirePolicy;
import com.nexora.persistence.ScheduleRecord;
import com.nexora.runtime.scheduler.CronScheduler;
import com.nexora.runtime.scheduler.ScheduledExecution;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
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
public final class NexoraEngine implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(NexoraEngine.class);

    private final ExecutionEngine engine;
    private final PluginManager pluginManager;
    private final ExecutionEventBus eventBus;
    private final CapabilityRegistry capabilityRegistry;
    private final CapabilityContractMonitor contractMonitor;
    private final CronScheduler cronScheduler;
    private final Tracer tracer;
    private final Executor executor;

    private NexoraEngine(
            ExecutionEngine engine,
            PluginManager pluginManager,
            ExecutionEventBus eventBus,
            CapabilityRegistry capabilityRegistry,
            CapabilityContractMonitor contractMonitor,
            CronScheduler cronScheduler,
            Tracer tracer,
            Executor executor) {
        this.engine = engine;
        this.pluginManager = pluginManager;
        this.eventBus = eventBus;
        this.capabilityRegistry = capabilityRegistry;
        this.contractMonitor = contractMonitor;
        this.cronScheduler = cronScheduler;
        this.tracer = tracer;
        this.executor = executor;
    }

    public CompletableFuture<ExecutionResult> execute(Intent intent) {
        return engine.execute(intent);
    }

    /**
     * Executes {@code intent} continuing a trace propagated in from an inbound request
     * (e.g. a {@code traceparent} header) instead of starting a fresh root trace.
     */
    public CompletableFuture<ExecutionResult> execute(Intent intent, TraceContext traceContext) {
        return engine.execute(intent, traceContext);
    }

    /**
     * Starts {@code intent} and returns immediately with its execution id and result future.
     * Use this instead of {@link #execute(Intent)} when the id is needed before the execution
     * finishes, for example to report it to a client or to {@link #cancel(String)} it.
     */
    public ExecutionHandle submit(Intent intent) {
        return engine.submit(intent, TraceContext.root());
    }

    /** {@link #submit(Intent)} continuing a trace propagated in from an inbound request. */
    public ExecutionHandle submit(Intent intent, TraceContext traceContext) {
        return engine.submit(intent, traceContext);
    }

    public CompletableFuture<ExecutionResult> execute(String goal, Map<String, Object> context) {
        return engine.execute(new Intent(goal, context));
    }

    /**
     * Convenience: execute with an inline per-call deadline, overriding any engine-wide default.
     */
    public CompletableFuture<ExecutionResult> execute(
            String goal,
            Map<String, Object> context,
            Duration deadline) {
        return engine.execute(new Intent(goal, context, deadline));
    }

    /**
     * Cancels a running execution: steps that have not started are never started, running steps
     * are interrupted, and the execution finishes as {@code CANCELLED} (with saga compensation for
     * completed steps when enabled). Safe to call more than once.
     *
     * <p>The returned future is already complete when this method returns: it reflects whether the
     * cancel request was accepted, not whether the execution has fully stopped; wait on the
     * execution's own future for that. It completes with {@code true} if the execution is being
     * cancelled, {@code false} if it already finished (or is already stopping on its deadline), or
     * exceptionally with {@link ExecutionNotFoundException} or {@link ExecutionNotOwnedException}.
     *
     * <p>Runs on the calling thread. Halting a running execution is an in-memory flag flip; only ids
     * this engine is not running fall back to a single store lookup.
     */
    public CompletableFuture<Boolean> cancel(String executionId) {
        if (executionId == null || executionId.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("executionId must not be blank"));
        }
        // Never queue this on the step executor: when every worker is busy running steps (e.g. a
        // single-thread executor), the cancel would wait behind the very step it has to interrupt.
        try {
            return switch (engine.cancel(executionId)) {
                case CANCELLED -> CompletableFuture.completedFuture(true);
                case ALREADY_TERMINAL -> CompletableFuture.completedFuture(false);
                case NOT_FOUND -> CompletableFuture.failedFuture(new ExecutionNotFoundException(executionId));
                case NOT_RUNNING_ON_THIS_ENGINE -> CompletableFuture.failedFuture(new ExecutionNotOwnedException(executionId));
            };
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    public <E extends ExecutionEvent> Subscription subscribe(Class<E> eventType, EventHandler<E> handler) {
        return eventBus.subscribe(eventType, handler);
    }

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

    public CapabilityContractMonitor.HealthSnapshot capabilityHealth(String capabilityId) {
        return contractMonitor.snapshot(capabilityId);
    }

    public com.nexora.persistence.ExecutionStore getStore() {
        return engine.getStore();
    }

    /**
     * Reports readiness of the engine's own dependencies: persistence, plugins, and the
     * shared executor. A {@code null} persistence store (persistence disabled) is reported
     * UP — there is nothing to check, not a degraded dependency.
     *
     * <p>Capability circuit breaker state is deliberately not part of readiness: an OPEN
     * circuit means a downstream is failing, and pulling every instance out of rotation
     * for that would turn a partial outage into a full one.
     */
    public ReadinessReport readiness() {
        Map<String, HealthStatus> checks = new LinkedHashMap<>();

        ExecutionStore store = engine.getStore();
        checks.put(ReadinessReport.CHECK_PERSISTENCE, HealthStatus.of(store == null || store.isHealthy()));

        checks.put(ReadinessReport.CHECK_PLUGINS, HealthStatus.of(pluginManager.nonActivePluginIds().isEmpty()));

        boolean executorUp = !(executor instanceof ExecutorService service) || !service.isShutdown();
        checks.put(ReadinessReport.CHECK_EXECUTOR, HealthStatus.of(executorUp));

        return new ReadinessReport(checks);
    }

    public enum HealthStatus {
        UP, DOWN;

        static HealthStatus of(boolean healthy) {
            return healthy ? UP : DOWN;
        }
    }

    /** Per-dependency readiness, in a stable check order. Ready only when every check is UP. */
    public record ReadinessReport(Map<String, HealthStatus> checks) {
        public static final String CHECK_PERSISTENCE = "persistence";
        public static final String CHECK_PLUGINS = "plugins";
        public static final String CHECK_EXECUTOR = "executor";

        public ReadinessReport {
            Objects.requireNonNull(checks, "checks must not be null");
            checks = Collections.unmodifiableMap(new LinkedHashMap<>(checks));
        }

        public boolean ready() {
            return checks.values().stream().allMatch(HealthStatus.UP::equals);
        }

        public HealthStatus status() {
            return HealthStatus.of(ready());
        }
    }

    /**
     * Schedules a recurring execution of {@code intent} on the given cron expression.
     *
     * @param cronExpression 5-field UNIX cron (minute hour day-of-month month day-of-week)
     * @param intent         intent to fire on each cron tick; only {@code goal} and {@code context} are persisted
     *                       and replayed - per-call fields such as deadlines are ignored for recurring ticks
     * @param missedFirePolicy behaviour when windows are missed after a restart
     * @return a {@link ScheduledExecution} handle; call {@link ScheduledExecution#cancel()} to stop
     * @throws IllegalStateException if no persistence store is configured
     */
    public ScheduledExecution schedule(String cronExpression, Intent intent, MissedFirePolicy missedFirePolicy) {
        requireScheduler();
        return cronScheduler.schedule(cronExpression, intent, missedFirePolicy);
    }

    /** Convenience: schedule with the default {@link MissedFirePolicy#FIRE_ONCE} policy. */
    public ScheduledExecution schedule(String cronExpression, Intent intent) {
        return schedule(cronExpression, intent, MissedFirePolicy.FIRE_ONCE);
    }

    /** Returns all schedules (active and inactive), most recently created first. */
    public List<ScheduleRecord> listSchedules() {
        requireScheduler();
        return cronScheduler.listAll();
    }

    /** Returns active schedules only, ordered by next fire time. */
    public List<ScheduleRecord> listActiveSchedules() {
        requireScheduler();
        return cronScheduler.listActive();
    }

    /** Cancels a schedule by id. Idempotent. */
    public void cancelSchedule(String scheduleId) {
        requireScheduler();
        cronScheduler.cancel(scheduleId);
    }

    /**
     * Shuts down the engine. Deactivates all plugins, stops the cron scheduler,
     * and closes the execution store. Safe to call multiple times.
     */
    public void shutdown() {
        pluginManager.activePluginIds().forEach(pluginManager::deactivatePlugin);
        if (cronScheduler != null) {
            cronScheduler.close();
        }
        if (engine.getStore() != null) {
            try {
                engine.getStore().close();
            } catch (Exception e) {
                log.warn("Error closing execution store during shutdown", e);
            }
        }
        if (tracer instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception e) {
                log.warn("Error closing tracer during shutdown", e);
            }
        }
    }

    @Override
    public void close() {
        shutdown();
    }

    private void requireScheduler() {
        if (cronScheduler == null) {
            throw new IllegalStateException(
                "Cron scheduling requires a persistence store. Call builder.withExecutionStore(...) first.");
        }
    }

    public record HealthSnapshot(String capabilityId, CapabilityContractMonitor.CircuitState state, int sampleCount, double errorRate, Duration p99Latency) {
        public static HealthSnapshot from(CapabilityContractMonitor.HealthSnapshot s) {
            return new HealthSnapshot(s.capabilityId(), s.state(), s.sampleCount(), s.errorRate(), s.p99Latency());
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private Executor executor = Executors.newVirtualThreadPerTaskExecutor();
        private Tracer tracer = NoopTracer.INSTANCE;
        private RetryPolicy defaultRetryPolicy = RetryPolicy.noRetry();
        private Duration defaultTimeout = null;
        private Duration defaultPlanDeadline = null;
        private ExecutionStore executionStore = null;
        private boolean sagaEnabled = false;
        private String webhookSecret = null;

        private final List<NexoraPlugin> plugins = new ArrayList<>();
        private final List<Path> pluginJars = new ArrayList<>();
        private final List<StepDefinition> stepDefinitions = new ArrayList<>();
        private final List<Planner> extraPlanners = new ArrayList<>();

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

        /**
         * Sets an engine-wide fallback deadline for all plan executions.
         * A per-intent deadline (set via {@link Intent#Intent(String, java.util.Map, Duration)})
         * always overrides this value.
         */
        public Builder withDefaultPlanDeadline(Duration deadline) {
            if (deadline == null || deadline.compareTo(Duration.ZERO) <= 0) {
                throw new IllegalArgumentException("defaultPlanDeadline must be a positive duration, got: " + deadline);
            }
            this.defaultPlanDeadline = deadline;
            return this;
        }

        public Builder withExecutionStore(ExecutionStore store) {
            this.executionStore = Objects.requireNonNull(store);
            return this;
        }

        public Builder withSagaEnabled(boolean enabled) {
            this.sagaEnabled = enabled;
            return this;
        }

        public Builder withWebhookSecret(String webhookSecret) {
            this.webhookSecret = webhookSecret;
            return this;
        }

        public Builder withPlugin(NexoraPlugin plugin) {
            plugins.add(Objects.requireNonNull(plugin));
            return this;
        }

        /**
         * Loads and activates a plugin JAR during {@link #build()}. Jar plugins activate after all
         * {@link #withPlugin(NexoraPlugin) inline plugins}, in the order added, so a jar plugin's
         * required plugins must be inline or added earlier. Activation failure fails {@code build()}.
         */
        public Builder withPluginJar(Path jar) {
            pluginJars.add(Objects.requireNonNull(jar));
            return this;
        }

        public Builder withStepDefinition(StepDefinition def) {
            stepDefinitions.add(Objects.requireNonNull(def));
            return this;
        }

        /** Register a custom planner. Plugin planners are registered automatically via withPlugin(). */
        public Builder withPlanner(Planner planner) {
            extraPlanners.add(Objects.requireNonNull(planner));
            return this;
        }

        public NexoraEngine build() {
            CapabilityRegistry capabilityRegistry = new DefaultCapabilityRegistry();
            InProcessEventBus eventBus = new InProcessEventBus(executor);
            PluginManager pluginManager = new PluginManager(capabilityRegistry, eventBus);

            for (NexoraPlugin plugin : plugins) {
                pluginManager.registerPlugin(plugin);
                pluginManager.activatePlugin(plugin.descriptor().id());
            }

            for (Path jar : pluginJars) {
                pluginManager.activatePlugin(pluginManager.loadPlugin(jar));
            }

            // Retry
            RetryPolicyRegistry retryPolicyRegistry = new DefaultRetryPolicyRegistry();
            retryPolicyRegistry.setDefault(defaultRetryPolicy);

            // Contract monitor
            CapabilityContractMonitor contractMonitor = new CapabilityContractMonitor(eventBus, capabilityRegistry);

            // Interceptor pipeline — Retry must wrap Tracing so each retry attempt
            // gets its own span with the correct attempt_number attribute.
            if (tracer == NoopTracer.INSTANCE) {
                String otlpEndpoint = System.getenv("OTEL_EXPORTER_OTLP_ENDPOINT");
                if (otlpEndpoint != null && !otlpEndpoint.isBlank()) {
                    log.warn("OTEL_EXPORTER_OTLP_ENDPOINT is set but no OtelTracer was wired via withTracer(); falling back to NoopTracer");
                }
            }
            List<ExecutionInterceptor> interceptors = List.of(
                    new RetryInterceptor(retryPolicyRegistry),
                    new TracingInterceptor(tracer),
                    new TimeoutInterceptor(executor, defaultTimeout)
            );
            InterceptorPipeline pipeline = new InterceptorPipeline(
                    interceptors,
                    new CapabilityInvoker(capabilityRegistry, contractMonitor)
            );

            // DAG scheduler
            DagStepScheduler scheduler = new DagStepScheduler(pipeline, retryPolicyRegistry, eventBus, executor, capabilityRegistry);

            // Planner — composite of plugin planners + rule planner as fallback
            PlanRegistry planRegistry = new PlanRegistry();
            stepDefinitions.forEach(planRegistry::register);
            PlannerEngine plannerEngine = new PlannerEngine(planRegistry);
            RulePlanner rulePlanner = new RulePlanner(plannerEngine);

            List<Planner> allPlanners = new ArrayList<>();
            allPlanners.addAll(extraPlanners);
            allPlanners.addAll(pluginManager.registeredPlanners());
            CompositePlanner compositePlanner = new CompositePlanner(allPlanners, rulePlanner);

            // Saga orchestrator (optional)
            SagaOrchestrator sagaOrchestrator = sagaEnabled
                    ? new SagaOrchestrator(pipeline, eventBus, executor)
                    : null;

            // Engine
            ExecutionEngine engine = new ExecutionEngine(
                    compositePlanner, capabilityRegistry, scheduler, eventBus,
                    executionStore, sagaOrchestrator, defaultPlanDeadline, executor, webhookSecret);

            // Cron scheduler (optional — requires a store)
            CronScheduler cronScheduler = executionStore != null
                    ? new CronScheduler(engine, executionStore, eventBus)
                    : null;

            return new NexoraEngine(engine, pluginManager, eventBus, capabilityRegistry, contractMonitor, cronScheduler, tracer, executor);
        }
    }
}
