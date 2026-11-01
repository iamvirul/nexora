package com.nexora.api;

import com.nexora.core.context.TraceContext;
import com.nexora.core.execution.ExecutionHandle;
import com.nexora.core.execution.ExecutionResult;
import com.nexora.core.intent.Intent;
import com.nexora.core.tenant.TenantId;
import com.nexora.event.EventHandler;
import com.nexora.event.ExecutionEvent;
import com.nexora.event.ExecutionEventBus;
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
import com.nexora.persistence.DeadLetterRecord;
import com.nexora.persistence.ExecutionRecord;
import com.nexora.persistence.ExecutionState;
import com.nexora.persistence.ExecutionStore;
import com.nexora.persistence.MissedFirePolicy;
import com.nexora.persistence.ScheduleRecord;
import com.nexora.persistence.StepOutput;
import com.nexora.persistence.StepRecord;
import com.nexora.persistence.StepState;
import com.nexora.planner.engine.CompositePlanner;
import com.nexora.planner.engine.PlannerEngine;
import com.nexora.planner.engine.RulePlanner;
import com.nexora.planner.model.StepDefinition;
import com.nexora.planner.registry.PlanRegistry;
import com.nexora.registry.DefaultCapabilityRegistry;
import com.nexora.retry.DefaultRetryPolicyRegistry;
import com.nexora.retry.RetryPolicy;
import com.nexora.retry.RetryPolicyRegistry;
import com.nexora.runtime.engine.ExecutionEngine;
import com.nexora.runtime.scheduler.CronScheduler;
import com.nexora.runtime.scheduler.ScheduledExecution;
import com.nexora.saga.SagaOrchestrator;
import com.nexora.spi.CapabilityRegistry;
import com.nexora.spi.NexoraPlugin;
import com.nexora.spi.Planner;
import com.nexora.tracing.NoopTracer;
import com.nexora.tracing.Tracer;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private final String tenantScope; // null = serves every tenant

    private NexoraEngine(
            ExecutionEngine engine,
            PluginManager pluginManager,
            ExecutionEventBus eventBus,
            CapabilityRegistry capabilityRegistry,
            CapabilityContractMonitor contractMonitor,
            CronScheduler cronScheduler,
            Tracer tracer,
            Executor executor,
            String tenantScope) {
        this.engine = engine;
        this.pluginManager = pluginManager;
        this.eventBus = eventBus;
        this.capabilityRegistry = capabilityRegistry;
        this.contractMonitor = contractMonitor;
        this.cronScheduler = cronScheduler;
        this.tracer = tracer;
        this.executor = executor;
        this.tenantScope = tenantScope;
    }

    /**
     * The only tenant this engine runs work for, set with {@link Builder#withTenantId(String)}.
     * Empty for an engine that serves every tenant, taking each intent's own tenant.
     */
    public Optional<String> tenantScope() {
        return Optional.ofNullable(tenantScope);
    }

    /**
     * The intent as this engine runs it. A tenant-bound engine gives an intent without a tenant its
     * own, and refuses an intent for any other tenant rather than run it under the wrong one.
     */
    private Intent scoped(Intent intent) {
        Objects.requireNonNull(intent, "intent must not be null");
        if (tenantScope == null || tenantScope.equals(intent.getTenantId())) {
            return intent;
        }
        if (TenantId.DEFAULT.equals(intent.getTenantId())) {
            return intent.withTenantId(tenantScope);
        }
        throw new IllegalArgumentException("Intent is for tenant '" + intent.getTenantId()
                + "' but this engine only runs work for tenant '" + tenantScope + "'");
    }

    /**
     * The tenant a tenant-scoped call reads within: {@code requested}, or this engine's own tenant
     * when none is requested. Empty when the caller asks for a tenant this engine does not serve.
     * A null value inside means every tenant.
     */
    private Optional<TenantFilter> filterFor(String requested) {
        if (requested != null) {
            TenantId.requireValid(requested);
        }
        if (tenantScope != null && requested != null && !tenantScope.equals(requested)) {
            return Optional.empty();
        }
        return Optional.of(new TenantFilter(requested != null ? requested : tenantScope));
    }

    /** @param tenantId the only tenant a call may touch; null for every tenant */
    private record TenantFilter(String tenantId) {
        boolean allows(String recordTenant) {
            return tenantId == null || tenantId.equals(recordTenant);
        }
    }

    public CompletableFuture<ExecutionResult> execute(Intent intent) {
        return engine.execute(scoped(intent));
    }

    /**
     * Executes {@code intent} continuing a trace propagated in from an inbound request
     * (e.g. a {@code traceparent} header) instead of starting a fresh root trace.
     */
    public CompletableFuture<ExecutionResult> execute(Intent intent, TraceContext traceContext) {
        return engine.execute(scoped(intent), traceContext);
    }

    /**
     * Starts {@code intent} and returns immediately with its execution id and result future.
     * Use this instead of {@link #execute(Intent)} when the id is needed before the execution
     * finishes, for example to report it to a client or to {@link #cancel(String)} it.
     */
    public ExecutionHandle submit(Intent intent) {
        return engine.submit(scoped(intent), TraceContext.root());
    }

    /** {@link #submit(Intent)} continuing a trace propagated in from an inbound request. */
    public ExecutionHandle submit(Intent intent, TraceContext traceContext) {
        return engine.submit(scoped(intent), traceContext);
    }

    public CompletableFuture<ExecutionResult> execute(String goal, Map<String, Object> context) {
        return execute(new Intent(goal, context));
    }

    /**
     * Convenience: execute with an inline per-call deadline, overriding any engine-wide default.
     */
    public CompletableFuture<ExecutionResult> execute(String goal, Map<String, Object> context, Duration deadline) {
        return execute(new Intent(goal, context, deadline));
    }

    /** Context key a replayed execution carries, holding the id of the execution it replays. */
    public static final String REPLAY_OF_CONTEXT_KEY = "_replayOf";

    private static final Set<ExecutionState> REPLAYABLE_STATES = EnumSet.of(
            ExecutionState.FAILED, ExecutionState.TIMED_OUT, ExecutionState.CANCELLED, ExecutionState.COMPENSATED);

    /**
     * Re-runs a finished, unsuccessful execution from the start with the same goal and context.
     * The replay gets a new execution id and trace id; its context carries the original id under
     * {@value #REPLAY_OF_CONTEXT_KEY}.
     *
     * <p>Steps that completed in the original are sent their original idempotency key, so an
     * idempotent capability does not repeat the side effect. Steps that failed get a fresh key: a
     * provider that caches responses per key would otherwise return the same failure. When the
     * original was {@code COMPENSATED}, its completed work was rolled back, so every step gets a
     * fresh key and is redone.
     *
     * <p>Only {@code FAILED}, {@code TIMED_OUT}, {@code CANCELLED} and {@code COMPENSATED} executions
     * can be replayed. The original's per-intent deadline and webhook are not persisted, so the
     * replay runs with the engine defaults.
     *
     * @return the handle of the new execution, returned as soon as it starts
     * @throws ExecutionNotFoundException       if no execution has this id
     * @throws ExecutionNotReplayableException  if it succeeded or is still running or compensating
     * @throws IllegalStateException            if the engine has no persistence store
     */
    public ExecutionHandle replay(String executionId) {
        return replay(null, executionId);
    }

    /**
     * {@link #replay(String)} limited to one tenant: an execution of any other tenant is reported as
     * not found. The replay runs for the original's tenant. A {@code null} tenant replays any
     * execution this engine serves.
     */
    public ExecutionHandle replay(String tenantId, String executionId) {
        if (executionId == null || executionId.isBlank()) {
            throw new IllegalArgumentException("executionId must not be blank");
        }
        ExecutionStore store = engine.getStore();
        if (store == null) {
            throw new IllegalStateException("Replay requires a persistence store");
        }
        TenantFilter filter = filterFor(tenantId).orElseThrow(() -> new ExecutionNotFoundException(executionId));
        // Looked up before the running check, so another tenant's live execution reads as not found.
        ExecutionRecord original = (filter.tenantId() == null
                        ? store.findById(executionId)
                        : store.findById(filter.tenantId(), executionId))
                .orElseThrow(() -> new ExecutionNotFoundException(executionId));
        // Still in terminal handling here, possibly about to compensate: its stored state is not final yet.
        if (engine.isRunning(executionId)) {
            throw new ExecutionNotReplayableException(executionId, ExecutionState.RUNNING);
        }
        if (!REPLAYABLE_STATES.contains(original.state())) {
            throw new ExecutionNotReplayableException(executionId, original.state());
        }

        Map<String, Object> context = new LinkedHashMap<>(original.context());
        context.put(REPLAY_OF_CONTEXT_KEY, executionId);
        Map<String, String> reusedKeys =
                original.state() == ExecutionState.COMPENSATED ? Map.of() : completedStepKeys(original);

        Intent intent = new Intent(original.goal(), context, null, null, null, original.tenantId());
        ExecutionHandle replay = engine.submit(intent, TraceContext.root(), reusedKeys);
        log.info(
                "Execution replay started executionId={} replayOf={} tenantId={} originalState={} reusedKeys={}",
                replay.executionId(),
                executionId,
                original.tenantId(),
                original.state(),
                reusedKeys.size());
        return replay;
    }

    /**
     * Replays the execution behind a dead letter via {@link #replay(String)}. If the original
     * execution record no longer exists, falls back to the goal and context the dead letter kept,
     * still tagged with {@value #REPLAY_OF_CONTEXT_KEY}. Marking the dead letter is up to the caller.
     *
     * @throws ExecutionNotReplayableException if the original execution is still running or compensating
     */
    public ExecutionHandle replayDeadLetter(DeadLetterRecord deadLetter) {
        Objects.requireNonNull(deadLetter, "deadLetter must not be null");
        // Checked before the fallback below: that path rebuilds the intent from the dead letter, and
        // scoped() would hand a foreign default-tenant goal to this engine's own tenant.
        if (filterFor(deadLetter.tenantId()).isEmpty()) {
            throw new ExecutionNotFoundException(deadLetter.executionId());
        }
        try {
            return replay(deadLetter.tenantId(), deadLetter.executionId());
        } catch (ExecutionNotFoundException e) {
            Map<String, Object> context = new LinkedHashMap<>(deadLetter.context());
            context.put(REPLAY_OF_CONTEXT_KEY, deadLetter.executionId());
            log.info(
                    "Dead letter replay without original record deadLetterId={} executionId={}",
                    deadLetter.id(),
                    deadLetter.executionId());
            Intent intent = new Intent(deadLetter.goal(), context, null, null, null, deadLetter.tenantId());
            return engine.submit(scoped(intent), TraceContext.root());
        }
    }

    private static Map<String, String> completedStepKeys(ExecutionRecord original) {
        Map<String, String> keys = new LinkedHashMap<>();
        for (StepRecord step : original.steps()) {
            if (step.state() == StepState.COMPLETED && step.idempotencyKey() != null) {
                keys.put(step.stepId(), step.idempotencyKey());
            }
        }
        return keys;
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
        return cancel(null, executionId);
    }

    /**
     * {@link #cancel(String)} limited to one tenant: an execution of any other tenant fails with
     * {@link ExecutionNotFoundException}, exactly like an unknown id.
     */
    public CompletableFuture<Boolean> cancel(String tenantId, String executionId) {
        if (executionId == null || executionId.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("executionId must not be blank"));
        }
        Optional<TenantFilter> filter;
        try {
            filter = filterFor(tenantId);
        } catch (IllegalArgumentException e) {
            return CompletableFuture.failedFuture(e);
        }
        if (filter.isEmpty()) {
            return CompletableFuture.failedFuture(new ExecutionNotFoundException(executionId));
        }
        // Never queue this on the step executor: when every worker is busy running steps (e.g. a
        // single-thread executor), the cancel would wait behind the very step it has to interrupt.
        try {
            return switch (engine.cancel(filter.get().tenantId(), executionId)) {
                case CANCELLED -> CompletableFuture.completedFuture(true);
                case ALREADY_TERMINAL -> CompletableFuture.completedFuture(false);
                case NOT_FOUND -> CompletableFuture.failedFuture(new ExecutionNotFoundException(executionId));
                case NOT_RUNNING_ON_THIS_ENGINE ->
                    CompletableFuture.failedFuture(new ExecutionNotOwnedException(executionId));
            };
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    public <E extends ExecutionEvent> Subscription subscribe(Class<E> eventType, EventHandler<E> handler) {
        return eventBus.subscribe(eventType, handler);
    }

    /**
     * Like {@link #subscribe}, but the handler runs on the thread that publishes the event, before
     * {@code publish} returns. Events of one execution then reach it in the order they happened,
     * which {@link #subscribe} does not promise. The handler holds up the execution while it runs,
     * so it must be quick and must not block.
     */
    public <E extends ExecutionEvent> Subscription subscribeSync(Class<E> eventType, EventHandler<E> handler) {
        return eventBus.subscribeSync(eventType, handler);
    }

    public void loadPlugin(Path pluginJar, String pluginId) {
        pluginManager.loadPlugin(pluginJar);
        pluginManager.activatePlugin(pluginId);
    }

    public List<com.nexora.spi.CapabilityDescriptor> listCapabilities() {
        return tenantScope == null ? capabilityRegistry.listAll() : capabilityRegistry.listFor(tenantScope);
    }

    /** The capabilities {@code tenantId} may use; empty for a tenant this engine does not serve. */
    public List<com.nexora.spi.CapabilityDescriptor> listCapabilities(String tenantId) {
        return filterFor(Objects.requireNonNull(tenantId, "tenantId must not be null"))
                .map(filter -> capabilityRegistry.listFor(filter.tenantId()))
                .orElse(List.of());
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
     * Cap on each recorded step output, in UTF-8 JSON bytes; 0 means outputs are not recorded.
     *
     * @see Builder#withStepOutputMaxBytes(int)
     */
    public int stepOutputMaxBytes() {
        return engine.stepOutputMaxBytes();
    }

    /**
     * Reports readiness of the engine's own dependencies: persistence, its schema migrations,
     * plugins, and the shared executor. A {@code null} persistence store (persistence disabled)
     * reports both persistence checks UP — there is nothing to check, not a degraded dependency.
     *
     * <p>Capability circuit breaker state is deliberately not part of readiness: an OPEN
     * circuit means a downstream is failing, and pulling every instance out of rotation
     * for that would turn a partial outage into a full one.
     */
    public ReadinessReport readiness() {
        Map<String, HealthStatus> checks = new LinkedHashMap<>();

        ExecutionStore store = engine.getStore();
        checks.put(ReadinessReport.CHECK_PERSISTENCE, HealthStatus.of(store == null || store.isHealthy()));
        checks.put(ReadinessReport.CHECK_MIGRATIONS, HealthStatus.of(store == null || store.isSchemaCurrent()));

        checks.put(
                ReadinessReport.CHECK_PLUGINS,
                HealthStatus.of(pluginManager.nonActivePluginIds().isEmpty()));

        boolean executorUp = !(executor instanceof ExecutorService service) || !service.isShutdown();
        checks.put(ReadinessReport.CHECK_EXECUTOR, HealthStatus.of(executorUp));

        return new ReadinessReport(checks);
    }

    public enum HealthStatus {
        UP,
        DOWN;

        static HealthStatus of(boolean healthy) {
            return healthy ? UP : DOWN;
        }
    }

    /** Per-dependency readiness, in a stable check order. Ready only when every check is UP. */
    public record ReadinessReport(Map<String, HealthStatus> checks) {
        public static final String CHECK_PERSISTENCE = "persistence";
        public static final String CHECK_MIGRATIONS = "migrations";
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
        return cronScheduler.schedule(cronExpression, scoped(intent), missedFirePolicy);
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
        if (tenantScope != null) {
            // A tenant-bound engine never touches another tenant's schedule, even through the unscoped call.
            cancelSchedule(null, scheduleId);
            return;
        }
        cronScheduler.cancel(scheduleId);
    }

    /** {@link #listSchedules()} limited to one tenant. */
    public List<ScheduleRecord> listSchedules(String tenantId) {
        requireScheduler();
        return filterFor(tenantId)
                .map(filter -> cronScheduler.listAll().stream()
                        .filter(r -> filter.allows(r.tenantId()))
                        .toList())
                .orElse(List.of());
    }

    /**
     * Cancels the schedule only if it belongs to {@code tenantId}.
     *
     * @return false when no schedule with this id belongs to that tenant; nothing is changed
     */
    public boolean cancelSchedule(String tenantId, String scheduleId) {
        requireScheduler();
        Optional<TenantFilter> filter = filterFor(tenantId);
        boolean owned = filter.isPresent()
                && cronScheduler
                        .find(scheduleId)
                        .filter(r -> filter.get().allows(r.tenantId()))
                        .isPresent();
        if (owned) {
            cronScheduler.cancel(scheduleId);
        }
        return owned;
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

    public record HealthSnapshot(
            String capabilityId,
            CapabilityContractMonitor.CircuitState state,
            int sampleCount,
            double errorRate,
            Duration p99Latency) {
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
        private int stepOutputMaxBytes = StepOutput.DEFAULT_MAX_BYTES;
        private String tenantId = null;

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

        /**
         * Caps each step output kept in the store and the process snapshot, in UTF-8 JSON bytes
         * (default {@link StepOutput#DEFAULT_MAX_BYTES}). A larger output is kept as a truncated
         * preview and logged at WARN; the step still completes and later steps still see all of it.
         * {@code 0} keeps no outputs, for workflows whose outputs must not be written anywhere.
         *
         * @throws IllegalArgumentException unless {@code maxBytes} is 0 or at least {@link StepOutput#MIN_MAX_BYTES}
         */
        public Builder withStepOutputMaxBytes(int maxBytes) {
            this.stepOutputMaxBytes = StepOutput.validateMaxBytes(maxBytes);
            return this;
        }

        /**
         * Binds the engine to one tenant, for library users who run an engine per tenant. Intents
         * without a tenant run for this one, intents for another tenant are refused, and replay,
         * cancel and schedule calls only see this tenant's work. Without it the engine serves every
         * tenant, each intent running for its own ({@link TenantId#DEFAULT} when it names none).
         *
         * <p>Bound and unbound engines can share a store. An unbound engine also loads this tenant's
         * schedules, but each firing is claimed in the store first, so it still runs exactly once.
         *
         * @throws IllegalArgumentException if {@code tenantId} is not a valid tenant id
         */
        public Builder withTenantId(String tenantId) {
            this.tenantId = TenantId.requireValid(tenantId);
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
                    log.warn(
                            "OTEL_EXPORTER_OTLP_ENDPOINT is set but no OtelTracer was wired via withTracer(); falling back to NoopTracer");
                }
            }
            List<ExecutionInterceptor> interceptors = List.of(
                    new RetryInterceptor(retryPolicyRegistry),
                    new TracingInterceptor(tracer),
                    new TimeoutInterceptor(executor, defaultTimeout));
            InterceptorPipeline pipeline =
                    new InterceptorPipeline(interceptors, new CapabilityInvoker(capabilityRegistry, contractMonitor));

            // DAG scheduler
            DagStepScheduler scheduler =
                    new DagStepScheduler(pipeline, retryPolicyRegistry, eventBus, executor, capabilityRegistry);

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
            SagaOrchestrator sagaOrchestrator = sagaEnabled ? new SagaOrchestrator(pipeline, eventBus, executor) : null;

            // Engine
            ExecutionEngine engine = new ExecutionEngine(
                    compositePlanner,
                    capabilityRegistry,
                    scheduler,
                    eventBus,
                    executionStore,
                    sagaOrchestrator,
                    defaultPlanDeadline,
                    executor,
                    webhookSecret,
                    stepOutputMaxBytes);

            // Cron scheduler (optional — requires a store)
            CronScheduler cronScheduler =
                    executionStore != null ? new CronScheduler(engine, executionStore, eventBus, tenantId) : null;

            return new NexoraEngine(
                    engine,
                    pluginManager,
                    eventBus,
                    capabilityRegistry,
                    contractMonitor,
                    cronScheduler,
                    tracer,
                    executor,
                    tenantId);
        }
    }
}
