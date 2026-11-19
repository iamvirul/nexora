package me.virul.nexora.api;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
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
import java.util.concurrent.atomic.AtomicReference;
import me.virul.nexora.core.context.TraceContext;
import me.virul.nexora.core.execution.ExecutionHandle;
import me.virul.nexora.core.execution.ExecutionResult;
import me.virul.nexora.core.intent.Intent;
import me.virul.nexora.core.tenant.TenantId;
import me.virul.nexora.event.EngineShutdownEvent;
import me.virul.nexora.event.EventHandler;
import me.virul.nexora.event.ExecutionEvent;
import me.virul.nexora.event.ExecutionEventBus;
import me.virul.nexora.event.InProcessEventBus;
import me.virul.nexora.event.Subscription;
import me.virul.nexora.executor.CapabilityContractMonitor;
import me.virul.nexora.executor.CapabilityInvoker;
import me.virul.nexora.executor.DagStepScheduler;
import me.virul.nexora.executor.ExecutionInterceptor;
import me.virul.nexora.executor.InterceptorPipeline;
import me.virul.nexora.executor.interceptor.RetryInterceptor;
import me.virul.nexora.executor.interceptor.TimeoutInterceptor;
import me.virul.nexora.executor.interceptor.TracingInterceptor;
import me.virul.nexora.loader.PluginManager;
import me.virul.nexora.loader.PluginReloadResult;
import me.virul.nexora.persistence.DeadLetterRecord;
import me.virul.nexora.persistence.ExecutionRecord;
import me.virul.nexora.persistence.ExecutionState;
import me.virul.nexora.persistence.ExecutionStore;
import me.virul.nexora.persistence.MissedFirePolicy;
import me.virul.nexora.persistence.ScheduleRecord;
import me.virul.nexora.persistence.StepOutput;
import me.virul.nexora.persistence.StepRecord;
import me.virul.nexora.persistence.StepState;
import me.virul.nexora.planner.engine.CompositePlanner;
import me.virul.nexora.planner.engine.PlannerEngine;
import me.virul.nexora.planner.engine.RulePlanner;
import me.virul.nexora.planner.model.StepDefinition;
import me.virul.nexora.planner.registry.PlanRegistry;
import me.virul.nexora.registry.DefaultCapabilityRegistry;
import me.virul.nexora.retry.DefaultRetryPolicyRegistry;
import me.virul.nexora.retry.RetryPolicy;
import me.virul.nexora.retry.RetryPolicyRegistry;
import me.virul.nexora.runtime.engine.DrainResult;
import me.virul.nexora.runtime.engine.EngineShuttingDownException;
import me.virul.nexora.runtime.engine.ExecutionEngine;
import me.virul.nexora.runtime.scheduler.CronScheduler;
import me.virul.nexora.runtime.scheduler.ScheduledExecution;
import me.virul.nexora.saga.SagaOrchestrator;
import me.virul.nexora.spi.CapabilityRegistry;
import me.virul.nexora.spi.NexoraPlugin;
import me.virul.nexora.spi.Planner;
import me.virul.nexora.tracing.NoopTracer;
import me.virul.nexora.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Public entry point for the Nexora execution platform.
 *
 * <p>Construct via {@link #builder()}: the builder wires all internal components.
 * All fields are effectively final after build(); the engine is thread-safe.
 *
 * <p>Example:
 * <pre>{@code
 * NexoraEngine engine = NexoraEngine.builder()
 *     .withPlugin(myPlugin)
 *     .withStepDefinition(new StepDefinition("validate", "validate_order", goal -> goal.contains("order")))
 *     .build();
 *
 * engine.execute(new Intent("process_order", Map.of("orderId", "123")))
 *       .thenAccept(result -> System.out.println(result.status()));
 * }</pre>
 *
 * <p>By default the engine registers a JVM shutdown hook that calls {@link #shutdown()}, so in-flight
 * executions are drained rather than abandoned when the process receives SIGTERM. See
 * {@link #shutdown(Duration)} and {@link Builder#withShutdownHook(boolean)}.
 */
public final class NexoraEngine implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(NexoraEngine.class);

    /**
     * How long {@link #shutdown()} waits for in-flight executions by default. With the cancel and
     * compensation wait that may follow, draining takes at most twice this, which leaves room within
     * the 30 second Kubernetes termination grace period for releasing plugins, the store and the tracer.
     */
    public static final Duration DEFAULT_SHUTDOWN_DRAIN_TIMEOUT = Duration.ofSeconds(10);

    private static final String SHUTDOWN_HOOK_THREAD_NAME = "nexora-engine-shutdown";

    private final ExecutionEngine engine;
    private final PluginManager pluginManager;
    private final ExecutionEventBus eventBus;
    private final CapabilityRegistry capabilityRegistry;
    private final CapabilityContractMonitor contractMonitor;
    private final CronScheduler cronScheduler;
    private final Tracer tracer;
    private final Executor executor;
    private final String tenantScope;
    private final Duration shutdownDrainTimeout;
    private final AtomicReference<CompletableFuture<DrainResult>> shutdownResult = new AtomicReference<>();
    private volatile Thread shutdownHook;

    private NexoraEngine(
            ExecutionEngine engine,
            PluginManager pluginManager,
            ExecutionEventBus eventBus,
            CapabilityRegistry capabilityRegistry,
            CapabilityContractMonitor contractMonitor,
            CronScheduler cronScheduler,
            Tracer tracer,
            Executor executor,
            String tenantScope,
            Duration shutdownDrainTimeout) {
        this.engine = engine;
        this.pluginManager = pluginManager;
        this.eventBus = eventBus;
        this.capabilityRegistry = capabilityRegistry;
        this.contractMonitor = contractMonitor;
        this.cronScheduler = cronScheduler;
        this.tracer = tracer;
        this.executor = executor;
        this.tenantScope = tenantScope;
        this.shutdownDrainTimeout = shutdownDrainTimeout;
    }

    /**
     * The only tenant this engine runs work for, set with {@link Builder#withTenantId(String)}.
     * Empty for an engine that serves every tenant, taking each intent's own tenant.
     *
     * @return the tenant id, or empty for an engine that serves every tenant
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

    /**
     * Plans and runs {@code intent} in a new root trace.
     *
     * @param intent the intent; on a tenant-bound engine, an intent without a tenant runs for the
     *               engine's tenant
     * @return completes with the result when the execution finishes
     * @throws NullPointerException        if {@code intent} is null
     * @throws IllegalArgumentException    if the intent is for a tenant this engine does not serve
     * @throws EngineShuttingDownException if {@link #shutdown(Duration)} has begun
     */
    public CompletableFuture<ExecutionResult> execute(Intent intent) {
        return engine.execute(scoped(intent));
    }

    /**
     * Executes {@code intent} continuing a trace propagated in from an inbound request
     * (e.g. a {@code traceparent} header) instead of starting a fresh root trace.
     *
     * @param intent       the intent
     * @param traceContext the inbound span the execution continues
     * @return completes with the result when the execution finishes
     * @throws IllegalArgumentException    if the intent is for a tenant this engine does not serve
     * @throws EngineShuttingDownException if {@link #shutdown(Duration)} has begun
     */
    public CompletableFuture<ExecutionResult> execute(Intent intent, TraceContext traceContext) {
        return engine.execute(scoped(intent), traceContext);
    }

    /**
     * Starts {@code intent} and returns immediately with its execution id and result future.
     * Use this instead of {@link #execute(Intent)} when the id is needed before the execution
     * finishes, for example to report it to a client or to {@link #cancel(String)} it.
     *
     * @param intent the intent
     * @return the execution id and the future of its result
     * @throws IllegalArgumentException    if the intent is for a tenant this engine does not serve
     * @throws EngineShuttingDownException if {@link #shutdown(Duration)} has begun
     */
    public ExecutionHandle submit(Intent intent) {
        return engine.submit(scoped(intent), TraceContext.root());
    }

    /**
     * {@link #submit(Intent)} continuing a trace propagated in from an inbound request.
     *
     * @param intent       the intent
     * @param traceContext the inbound span the execution continues
     * @return the execution id and the future of its result
     * @throws IllegalArgumentException    if the intent is for a tenant this engine does not serve
     * @throws EngineShuttingDownException if {@link #shutdown(Duration)} has begun
     */
    public ExecutionHandle submit(Intent intent, TraceContext traceContext) {
        return engine.submit(scoped(intent), traceContext);
    }

    /**
     * Convenience: {@link #execute(Intent)} for a new intent with no per-intent deadline.
     *
     * @param goal    the goal
     * @param context the intent context, or {@code null} for none
     * @return completes with the result when the execution finishes
     * @throws EngineShuttingDownException if {@link #shutdown(Duration)} has begun
     */
    public CompletableFuture<ExecutionResult> execute(String goal, Map<String, Object> context) {
        return execute(new Intent(goal, context));
    }

    /**
     * Convenience: execute with an inline per-call deadline, overriding any engine-wide default.
     *
     * @param goal     the goal
     * @param context  the intent context, or {@code null} for none
     * @param deadline the wall-clock limit for the whole execution, or {@code null} for the default
     * @return completes with the result when the execution finishes
     * @throws IllegalArgumentException    if {@code deadline} is not positive
     * @throws EngineShuttingDownException if {@link #shutdown(Duration)} has begun
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
     * @param executionId the execution to replay
     * @return the handle of the new execution, returned as soon as it starts
     * @throws IllegalArgumentException        if {@code executionId} is null or blank
     * @throws ExecutionNotFoundException       if no execution has this id
     * @throws ExecutionNotReplayableException  if it succeeded or is still running or compensating
     * @throws IllegalStateException            if the engine has no persistence store
     * @throws EngineShuttingDownException      if shutdown has begun
     */
    public ExecutionHandle replay(String executionId) {
        return replay(null, executionId);
    }

    /**
     * {@link #replay(String)} limited to one tenant: an execution of any other tenant is reported as
     * not found. The replay runs for the original's tenant. A {@code null} tenant replays any
     * execution this engine serves.
     *
     * @param tenantId    the tenant the execution must belong to, or {@code null} for any
     * @param executionId the execution to replay
     * @return the handle of the new execution
     * @throws IllegalArgumentException        if {@code executionId} is blank or {@code tenantId} is
     *                                         not a valid tenant id
     * @throws ExecutionNotFoundException      if no execution of that tenant has this id
     * @throws ExecutionNotReplayableException if it succeeded or is still running or compensating
     * @throws IllegalStateException           if the engine has no persistence store
     * @throws EngineShuttingDownException     if shutdown has begun
     */
    public ExecutionHandle replay(String tenantId, String executionId) {
        if (executionId == null || executionId.isBlank()) {
            throw new IllegalArgumentException("executionId must not be blank");
        }
        if (!engine.isAcceptingExecutions()) {
            throw new EngineShuttingDownException();
        }
        ExecutionStore store = engine.getStore();
        if (store == null) {
            throw new IllegalStateException("Replay requires a persistence store");
        }
        TenantFilter filter = filterFor(tenantId).orElseThrow(() -> new ExecutionNotFoundException(executionId));
        ExecutionRecord original = (filter.tenantId() == null
                        ? store.findById(executionId)
                        : store.findById(filter.tenantId(), executionId))
                .orElseThrow(() -> new ExecutionNotFoundException(executionId));
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
     * @param deadLetter the dead letter to replay
     * @return the handle of the new execution
     * @throws NullPointerException            if {@code deadLetter} is null
     * @throws ExecutionNotFoundException      if the dead letter belongs to a tenant this engine does
     *                                         not serve
     * @throws ExecutionNotReplayableException if the original execution is still running or compensating
     * @throws EngineShuttingDownException     if shutdown has begun
     */
    public ExecutionHandle replayDeadLetter(DeadLetterRecord deadLetter) {
        Objects.requireNonNull(deadLetter, "deadLetter must not be null");
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
     *
     * @param executionId the execution to cancel
     * @return a completed future, see above; it fails with {@link IllegalArgumentException} for a
     *         blank id
     */
    public CompletableFuture<Boolean> cancel(String executionId) {
        return cancel(null, executionId);
    }

    /**
     * {@link #cancel(String)} limited to one tenant: an execution of any other tenant fails with
     * {@link ExecutionNotFoundException}, exactly like an unknown id.
     *
     * @param tenantId    the tenant the execution must belong to, or {@code null} for any
     * @param executionId the execution to cancel
     * @return a completed future, as for {@link #cancel(String)}; it fails with
     *         {@link IllegalArgumentException} for a blank id or an invalid tenant id
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

    /**
     * Subscribes to the events of every execution. The handler runs asynchronously on the engine's
     * executor, so events can reach it out of order; use {@link #subscribeSync} when order matters.
     *
     * @param <E>       the event type
     * @param eventType the event class; only events of exactly this class are delivered
     * @param handler   called for each event
     * @return the subscription, to cancel it
     */
    public <E extends ExecutionEvent> Subscription subscribe(Class<E> eventType, EventHandler<E> handler) {
        return eventBus.subscribe(eventType, handler);
    }

    /**
     * Like {@link #subscribe}, but the handler runs on the thread that publishes the event, before
     * {@code publish} returns. Events of one execution then reach it in the order they happened,
     * which {@link #subscribe} does not promise. The handler holds up the execution while it runs,
     * so it must be quick and must not block.
     *
     * @param <E>       the event type
     * @param eventType the event class; only events of exactly this class are delivered
     * @param handler   called for each event, on the publishing thread
     * @return the subscription, to cancel it
     */
    public <E extends ExecutionEvent> Subscription subscribeSync(Class<E> eventType, EventHandler<E> handler) {
        return eventBus.subscribeSync(eventType, handler);
    }

    /**
     * Loads a plugin JAR and activates it.
     *
     * @param pluginJar the plugin JAR
     * @param pluginId  the id the plugin declares in its descriptor
     * @throws me.virul.nexora.spi.PluginInitializationException if the plugin cannot be loaded or started
     */
    public void loadPlugin(Path pluginJar, String pluginId) {
        pluginManager.loadPlugin(pluginJar);
        pluginManager.activatePlugin(pluginId);
    }

    /**
     * Swaps an active JAR plugin for the version in {@code newJar} without stopping the engine.
     *
     * <p>New executions use the new version's capabilities and planners as soon as this returns.
     * Steps already running on the old version finish on it; the old version is shut down and its
     * class loader closed when the last of them returns. If the new JAR cannot replace the old one,
     * the old version stays active, a {@link me.virul.nexora.event.PluginReloadFailedEvent} is published
     * and {@link me.virul.nexora.spi.PluginInitializationException} is thrown; on success a
     * {@link me.virul.nexora.event.PluginReloadedEvent} carries both versions.
     *
     * <p>Requires an {@link ExecutionStore}: without one, executions interrupted around a reload
     * leave no record to inspect or {@link #replay(String) replay}.
     *
     * @param pluginId the active plugin to replace
     * @param newJar   the JAR of the new version
     * @return the old and new versions
     * @throws IllegalArgumentException if {@code pluginId} is null or blank
     * @throws IllegalStateException if no execution store is configured, or the plugin is not an
     *                               active JAR plugin
     * @see PluginManager#reloadPlugin(String, Path)
     */
    public PluginReloadResult reloadPlugin(String pluginId, Path newJar) {
        if (pluginId == null || pluginId.isBlank()) {
            throw new IllegalArgumentException("pluginId must not be blank");
        }
        Objects.requireNonNull(newJar, "newJar must not be null");
        if (engine.getStore() == null) {
            throw new IllegalStateException("Plugin hot reload requires an ExecutionStore so executions running"
                    + " across the swap stay recorded; configure one with builder.withExecutionStore(...),"
                    + " or restart the engine to pick up the new plugin version.");
        }
        return pluginManager.reloadPlugin(pluginId, newJar);
    }

    /**
     * The registered capabilities; on a tenant-bound engine, only those its tenant may use.
     *
     * @return the descriptors
     */
    public List<me.virul.nexora.spi.CapabilityDescriptor> listCapabilities() {
        return tenantScope == null ? capabilityRegistry.listAll() : capabilityRegistry.listFor(tenantScope);
    }

    /**
     * The capabilities {@code tenantId} may use; empty for a tenant this engine does not serve.
     *
     * @param tenantId the tenant
     * @return the descriptors
     * @throws NullPointerException     if {@code tenantId} is null
     * @throws IllegalArgumentException if it is not a valid tenant id
     */
    public List<me.virul.nexora.spi.CapabilityDescriptor> listCapabilities(String tenantId) {
        return filterFor(Objects.requireNonNull(tenantId, "tenantId must not be null"))
                .map(filter -> capabilityRegistry.listFor(filter.tenantId()))
                .orElse(List.of());
    }

    /**
     * The JAR the active version of {@code pluginId} was loaded from. Empty for a plugin that is not
     * loaded or was registered in code with {@link Builder#withPlugin(NexoraPlugin)}.
     *
     * @param pluginId the plugin
     * @return the JAR path, or empty
     */
    public Optional<Path> pluginJar(String pluginId) {
        return pluginManager.jar(pluginId);
    }

    /**
     * The plugins that are currently active.
     *
     * @return their ids
     */
    public List<String> activePluginIds() {
        return pluginManager.activePluginIds();
    }

    /**
     * The circuit breaker state and recent metrics of a capability's contract.
     *
     * @param capabilityId the capability
     * @return a snapshot; see {@link HealthSnapshot#from} for a copy that carries no executor types
     */
    public CapabilityContractMonitor.HealthSnapshot capabilityHealth(String capabilityId) {
        return contractMonitor.snapshot(capabilityId);
    }

    /**
     * The execution store set with {@link Builder#withExecutionStore(ExecutionStore)}.
     *
     * @return the store, or {@code null} when persistence is disabled
     */
    public me.virul.nexora.persistence.ExecutionStore getStore() {
        return engine.getStore();
    }

    /**
     * Cap on each recorded step output, in UTF-8 JSON bytes; 0 means outputs are not recorded.
     *
     * @return the cap in bytes
     * @see Builder#withStepOutputMaxBytes(int)
     */
    public int stepOutputMaxBytes() {
        return engine.stepOutputMaxBytes();
    }

    /**
     * Reports readiness of the engine's own dependencies: persistence, its schema migrations,
     * plugins, and the shared executor. The executor check also reports DOWN once shutdown has begun,
     * so a load balancer stops routing work the engine would reject. A {@code null} persistence store (persistence disabled)
     * reports both persistence checks UP: there is nothing to check, not a degraded dependency.
     * When persistence is DOWN the schema is not checked and migrations report DOWN too, so a probe
     * waits for a database connection at most once.
     *
     * <p>Capability circuit breaker state is deliberately not part of readiness: an OPEN
     * circuit means a downstream is failing, and pulling every instance out of rotation
     * for that would turn a partial outage into a full one.
     *
     * @return the status of each check
     */
    public ReadinessReport readiness() {
        Map<String, HealthStatus> checks = new LinkedHashMap<>();

        ExecutionStore store = engine.getStore();
        boolean persistenceUp = store == null || store.isHealthy();
        checks.put(ReadinessReport.CHECK_PERSISTENCE, HealthStatus.of(persistenceUp));
        checks.put(
                ReadinessReport.CHECK_MIGRATIONS,
                HealthStatus.of(persistenceUp && (store == null || store.isSchemaCurrent())));

        checks.put(
                ReadinessReport.CHECK_PLUGINS,
                HealthStatus.of(pluginManager.nonActivePluginIds().isEmpty()));

        boolean executorUp = engine.isAcceptingExecutions()
                && (!(executor instanceof ExecutorService service) || !service.isShutdown());
        checks.put(ReadinessReport.CHECK_EXECUTOR, HealthStatus.of(executorUp));

        return new ReadinessReport(checks);
    }

    /** The status of one readiness check. */
    public enum HealthStatus {
        /** The dependency is ready. */
        UP,
        /** The dependency is not ready. */
        DOWN;

        static HealthStatus of(boolean healthy) {
            return healthy ? UP : DOWN;
        }
    }

    /**
     * Per-dependency readiness, in a stable check order. Ready only when every check is UP.
     *
     * @param checks the status of each check by name, such as {@link #CHECK_PERSISTENCE}; immutable
     */
    public record ReadinessReport(Map<String, HealthStatus> checks) {
        /** The execution store answers. */
        public static final String CHECK_PERSISTENCE = "persistence";
        /** The database schema is at the version this release migrates to. */
        public static final String CHECK_MIGRATIONS = "migrations";
        /** Every loaded plugin is active. */
        public static final String CHECK_PLUGINS = "plugins";
        /** The engine accepts executions and its executor is not shut down. */
        public static final String CHECK_EXECUTOR = "executor";

        /**
         * Copies the checks, keeping their order.
         *
         * @throws NullPointerException if {@code checks} is null
         */
        public ReadinessReport {
            Objects.requireNonNull(checks, "checks must not be null");
            checks = Collections.unmodifiableMap(new LinkedHashMap<>(checks));
        }

        /**
         * Whether every check is UP.
         *
         * @return {@code true} when ready
         */
        public boolean ready() {
            return checks.values().stream().allMatch(HealthStatus.UP::equals);
        }

        /**
         * The overall status.
         *
         * @return {@link HealthStatus#UP} when {@link #ready()}, otherwise {@link HealthStatus#DOWN}
         */
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
     * @throws IllegalArgumentException if the cron expression is invalid, or the intent is for a tenant
     *                                  this engine does not serve
     */
    public ScheduledExecution schedule(String cronExpression, Intent intent, MissedFirePolicy missedFirePolicy) {
        requireScheduler();
        return cronScheduler.schedule(cronExpression, scoped(intent), missedFirePolicy);
    }

    /**
     * Convenience: schedule with the default {@link MissedFirePolicy#FIRE_ONCE} policy.
     *
     * @param cronExpression 5-field UNIX cron (minute hour day-of-month month day-of-week)
     * @param intent         intent to fire on each cron tick
     * @return a {@link ScheduledExecution} handle
     * @throws IllegalStateException    if no persistence store is configured
     * @throws IllegalArgumentException if the cron expression is invalid
     */
    public ScheduledExecution schedule(String cronExpression, Intent intent) {
        return schedule(cronExpression, intent, MissedFirePolicy.FIRE_ONCE);
    }

    /**
     * Returns all schedules (active and inactive), most recently created first.
     *
     * @return the schedules
     * @throws IllegalStateException if no persistence store is configured
     */
    public List<ScheduleRecord> listSchedules() {
        requireScheduler();
        return cronScheduler.listAll();
    }

    /**
     * Returns active schedules only, ordered by next fire time.
     *
     * @return the active schedules
     * @throws IllegalStateException if no persistence store is configured
     */
    public List<ScheduleRecord> listActiveSchedules() {
        requireScheduler();
        return cronScheduler.listActive();
    }

    /**
     * Cancels a schedule by id. Idempotent. A tenant-bound engine only cancels its own tenant's
     * schedules.
     *
     * @param scheduleId the schedule
     * @throws IllegalStateException if no persistence store is configured
     */
    public void cancelSchedule(String scheduleId) {
        requireScheduler();
        if (tenantScope != null) {
            cancelSchedule(null, scheduleId);
            return;
        }
        cronScheduler.cancel(scheduleId);
    }

    /**
     * {@link #listSchedules()} limited to one tenant.
     *
     * @param tenantId the tenant, or {@code null} for every tenant this engine serves
     * @return the schedules; empty for a tenant this engine does not serve
     * @throws IllegalStateException    if no persistence store is configured
     * @throws IllegalArgumentException if {@code tenantId} is not a valid tenant id
     */
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
     * @param tenantId   the tenant, or {@code null} for every tenant this engine serves
     * @param scheduleId the schedule
     * @return false when no schedule with this id belongs to that tenant; nothing is changed
     * @throws IllegalStateException    if no persistence store is configured
     * @throws IllegalArgumentException if {@code tenantId} is not a valid tenant id
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
     * False once shutdown has begun; from then on every new execution is rejected.
     *
     * @return whether new executions are accepted
     */
    public boolean isAcceptingExecutions() {
        return engine.isAcceptingExecutions();
    }

    /**
     * {@link #shutdown(Duration)} with the drain timeout set by
     * {@link Builder#withShutdownDrainTimeout(Duration)}, {@link #DEFAULT_SHUTDOWN_DRAIN_TIMEOUT} unless set.
     */
    public void shutdown() {
        shutdown(shutdownDrainTimeout);
    }

    /**
     * Shuts the engine down gracefully:
     * <ol>
     *   <li>New executions, replays and cron firings are rejected with {@link EngineShuttingDownException},
     *       and {@link #readiness()} reports DOWN.</li>
     *   <li>In-flight executions get up to {@code drainTimeout} to finish on their own.</li>
     *   <li>Any still running are cancelled: running steps are interrupted and, when saga is enabled,
     *       completed steps are compensated. Shutdown waits up to {@code drainTimeout} again for that.</li>
     *   <li>An {@link EngineShutdownEvent} carrying the drain counts is published.</li>
     *   <li>Plugins are deactivated and the execution store and tracer are closed.</li>
     * </ol>
     *
     * <p>Only the first call shuts down; later and concurrent calls, including the JVM shutdown hook,
     * wait for it and return its result. An executor passed with {@link Builder#withExecutor} is the
     * caller's and is not shut down.
     *
     * @param drainTimeout how long to wait for in-flight executions; zero cancels them at once
     * @return what happened to the executions in flight
     * @throws IllegalArgumentException if {@code drainTimeout} is negative
     */
    public DrainResult shutdown(Duration drainTimeout) {
        requireValidDrainTimeout(drainTimeout);
        CompletableFuture<DrainResult> mine = new CompletableFuture<>();
        CompletableFuture<DrainResult> first = shutdownResult.compareAndExchange(null, mine);
        if (first != null) {
            return first.join();
        }
        try {
            mine.complete(drainAndRelease(drainTimeout));
        } catch (RuntimeException | Error e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            removeShutdownHook();
        }
        return mine.join();
    }

    private DrainResult drainAndRelease(Duration drainTimeout) {
        log.info("Engine shutdown started drainTimeout={}", drainTimeout);
        if (cronScheduler != null) {
            cronScheduler.close();
        }
        DrainResult drain = engine.drain(drainTimeout);
        eventBus.publish(new EngineShutdownEvent(
                drain.inFlight(),
                drain.completed(),
                drain.cancelled(),
                drain.unfinished(),
                drain.elapsed(),
                Instant.now()));
        releasePlugins();
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
        log.info(
                "Engine shutdown complete inFlight={} completed={} cancelled={} unfinished={} elapsed={}ms",
                drain.inFlight(),
                drain.completed(),
                drain.cancelled(),
                drain.unfinished(),
                drain.elapsed().toMillis());
        return drain;
    }

    /**
     * Deactivates every active plugin, dependents before the plugins they require. The manager keeps no
     * activation order and refuses to release a plugin an active one still requires, so each pass
     * retries the refused ones until a pass releases nothing. What is left is logged, and shutdown
     * goes on to close the store and tracer either way.
     */
    private void releasePlugins() {
        List<String> remaining = pluginManager.activePluginIds();
        while (!remaining.isEmpty()) {
            Map<String, RuntimeException> refused = new LinkedHashMap<>();
            for (String pluginId : remaining) {
                try {
                    pluginManager.deactivatePlugin(pluginId);
                } catch (RuntimeException e) {
                    refused.put(pluginId, e);
                }
            }
            if (refused.size() == remaining.size()) {
                refused.forEach((pluginId, e) ->
                        log.warn("Plugin deactivation failed during shutdown pluginId={}", pluginId, e));
                return;
            }
            remaining = List.copyOf(refused.keySet());
        }
    }

    private void installShutdownHook() {
        Thread hook = Thread.ofPlatform().name(SHUTDOWN_HOOK_THREAD_NAME).unstarted(() -> {
            try {
                shutdown();
            } catch (RuntimeException e) {
                log.error("Engine shutdown from JVM shutdown hook failed", e);
            }
        });
        Runtime.getRuntime().addShutdownHook(hook);
        shutdownHook = hook;
    }

    /** Whether a JVM shutdown hook is registered for this engine. */
    boolean shutdownHookRegistered() {
        return shutdownHook != null;
    }

    /** Unregisters the JVM hook so an engine shut down explicitly is not kept reachable until exit. */
    private void removeShutdownHook() {
        Thread hook = shutdownHook;
        if (hook == null || hook == Thread.currentThread()) {
            return;
        }
        try {
            Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException e) {
            log.debug("JVM shutdown in progress; shutdown hook left registered");
        }
        shutdownHook = null;
    }

    private static Duration requireValidDrainTimeout(Duration drainTimeout) {
        Objects.requireNonNull(drainTimeout, "drainTimeout must not be null");
        if (drainTimeout.isNegative()) {
            throw new IllegalArgumentException("drainTimeout must not be negative, got: " + drainTimeout);
        }
        return drainTimeout;
    }

    /** {@link #shutdown()}. */
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

    /**
     * The contract health of one capability, as reported by {@link #capabilityHealth(String)}.
     *
     * @param capabilityId the capability
     * @param state        the circuit breaker state
     * @param sampleCount  the number of calls in the sliding window
     * @param errorRate    the share of failed calls in the window, from {@code 0.0} to {@code 1.0}
     * @param p99Latency   the p99 latency over the window
     */
    public record HealthSnapshot(
            String capabilityId,
            CapabilityContractMonitor.CircuitState state,
            int sampleCount,
            double errorRate,
            Duration p99Latency) {

        /**
         * Copies a contract monitor snapshot.
         *
         * @param s the snapshot
         * @return the copy
         */
        public static HealthSnapshot from(CapabilityContractMonitor.HealthSnapshot s) {
            return new HealthSnapshot(s.capabilityId(), s.state(), s.sampleCount(), s.errorRate(), s.p99Latency());
        }
    }

    /**
     * Starts building an engine.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Configures and builds a {@link NexoraEngine}. Every setting is optional. Not thread-safe; build one
     * engine per builder.
     */
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
        private Duration shutdownDrainTimeout = DEFAULT_SHUTDOWN_DRAIN_TIMEOUT;
        private boolean shutdownHookEnabled = true;

        private final List<NexoraPlugin> plugins = new ArrayList<>();
        private final List<Path> pluginJars = new ArrayList<>();
        private final List<StepDefinition> stepDefinitions = new ArrayList<>();
        private final List<Planner> extraPlanners = new ArrayList<>();

        /** Creates a builder with the defaults described on each setting. */
        public Builder() {}

        /**
         * The executor that runs steps, event handlers and timeouts. Defaults to a virtual thread per
         * task. The engine does not shut down an executor passed here.
         *
         * @param e the executor
         * @return this builder
         * @throws NullPointerException if {@code e} is null
         */
        public Builder withExecutor(Executor e) {
            this.executor = Objects.requireNonNull(e);
            return this;
        }

        /**
         * The tracer that records a span per capability call. Defaults to a no-op tracer. A tracer that
         * is {@link AutoCloseable} is closed on shutdown.
         *
         * @param t the tracer
         * @return this builder
         * @throws NullPointerException if {@code t} is null
         */
        public Builder withTracer(Tracer t) {
            this.tracer = Objects.requireNonNull(t);
            return this;
        }

        /**
         * The retry policy of every step. Defaults to {@link RetryPolicy#noRetry()}.
         *
         * @param policy the policy
         * @return this builder
         * @throws NullPointerException if {@code policy} is null
         */
        public Builder withDefaultRetryPolicy(RetryPolicy policy) {
            this.defaultRetryPolicy = Objects.requireNonNull(policy);
            return this;
        }

        /**
         * Per-step timeout for steps that set none; {@code null} (the default) means no timeout.
         *
         * @param timeout the timeout of each attempt, or {@code null}
         * @return this builder
         * @throws IllegalArgumentException if {@code timeout} is under 1ms: the timeout is applied in
         *     whole milliseconds, so a shorter one would fail every step at once
         */
        public Builder withDefaultTimeout(Duration timeout) {
            if (timeout != null && timeout.toMillis() < 1) {
                throw new IllegalArgumentException("defaultTimeout must be at least 1ms, got: " + timeout);
            }
            this.defaultTimeout = timeout;
            return this;
        }

        /**
         * Sets an engine-wide fallback deadline for all plan executions.
         * A per-intent deadline (set via {@link Intent#Intent(String, java.util.Map, Duration)})
         * always overrides this value.
         *
         * @param deadline the wall-clock limit for an execution
         * @return this builder
         * @throws IllegalArgumentException if {@code deadline} is null or not positive
         */
        public Builder withDefaultPlanDeadline(Duration deadline) {
            if (deadline == null || deadline.compareTo(Duration.ZERO) <= 0) {
                throw new IllegalArgumentException("defaultPlanDeadline must be a positive duration, got: " + deadline);
            }
            this.defaultPlanDeadline = deadline;
            return this;
        }

        /**
         * Persists executions, dead letters and schedules. Without a store, executions are not
         * recorded, and {@link NexoraEngine#replay(String) replay}, cron schedules and plugin hot
         * reload are unavailable.
         *
         * @param store the store; closed on shutdown
         * @return this builder
         * @throws NullPointerException if {@code store} is null
         */
        public Builder withExecutionStore(ExecutionStore store) {
            this.executionStore = Objects.requireNonNull(store);
            return this;
        }

        /**
         * Whether a failed, timed-out or cancelled execution compensates its completed steps, in reverse
         * dependency order,
         * with each step's {@link me.virul.nexora.core.plan.Step#compensateCapabilityId()}. Off by default.
         *
         * @param enabled {@code true} to compensate
         * @return this builder
         */
        public Builder withSagaEnabled(boolean enabled) {
            this.sagaEnabled = enabled;
            return this;
        }

        /**
         * The key webhook payloads are signed with: each delivery carries a {@code nexora-signature}
         * header holding the HMAC-SHA256 of its body. Unset, payloads are signed with an empty key, so
         * receivers cannot verify them; set one whenever intents use webhooks.
         *
         * @param webhookSecret the signing key, or {@code null}
         * @return this builder
         */
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
         * @param maxBytes the cap in bytes
         * @return this builder
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
         * @param tenantId the tenant
         * @return this builder
         * @throws IllegalArgumentException if {@code tenantId} is not a valid tenant id
         */
        public Builder withTenantId(String tenantId) {
            this.tenantId = TenantId.requireValid(tenantId);
            return this;
        }

        /**
         * How long {@link NexoraEngine#shutdown()}, {@link NexoraEngine#close()} and the JVM shutdown
         * hook wait for in-flight executions before cancelling them. Defaults to
         * {@link #DEFAULT_SHUTDOWN_DRAIN_TIMEOUT}; zero cancels them at once.
         *
         * @param drainTimeout how long to wait
         * @return this builder
         * @throws IllegalArgumentException if {@code drainTimeout} is negative
         */
        public Builder withShutdownDrainTimeout(Duration drainTimeout) {
            this.shutdownDrainTimeout = requireValidDrainTimeout(drainTimeout);
            return this;
        }

        /**
         * Whether to register a JVM shutdown hook that calls {@link NexoraEngine#shutdown()}. On by
         * default; turn it off when a container such as Spring already closes the engine on exit.
         *
         * @param enabled {@code true} to register the hook
         * @return this builder
         */
        public Builder withShutdownHook(boolean enabled) {
            this.shutdownHookEnabled = enabled;
            return this;
        }

        /**
         * Registers a plugin instance, activated during {@link #build()} in the order added.
         *
         * @param plugin the plugin
         * @return this builder
         * @throws NullPointerException if {@code plugin} is null
         */
        public Builder withPlugin(NexoraPlugin plugin) {
            plugins.add(Objects.requireNonNull(plugin));
            return this;
        }

        /**
         * Loads and activates a plugin JAR during {@link #build()}. Jar plugins activate after all
         * {@link #withPlugin(NexoraPlugin) inline plugins}, in the order added, so a jar plugin's
         * required plugins must be inline or added earlier. Activation failure fails {@code build()}.
         *
         * @param jar the plugin JAR
         * @return this builder
         * @throws NullPointerException if {@code jar} is null
         */
        public Builder withPluginJar(Path jar) {
            pluginJars.add(Objects.requireNonNull(jar));
            return this;
        }

        /**
         * Adds a step to the built-in rule planner: it becomes part of the plan of every intent whose
         * goal it matches.
         *
         * @param def the step definition
         * @return this builder
         * @throws NullPointerException if {@code def} is null
         */
        public Builder withStepDefinition(StepDefinition def) {
            stepDefinitions.add(Objects.requireNonNull(def));
            return this;
        }

        /**
         * Register a custom planner. Plugin planners are registered automatically via withPlugin().
         * Custom and plugin planners are tried by priority before the built-in rule planner.
         *
         * @param planner the planner
         * @return this builder
         * @throws NullPointerException if {@code planner} is null
         */
        public Builder withPlanner(Planner planner) {
            extraPlanners.add(Objects.requireNonNull(planner));
            return this;
        }

        /**
         * Wires the engine and activates its plugins.
         *
         * @return the engine, accepting executions
         * @throws me.virul.nexora.spi.PluginInitializationException if a plugin cannot be loaded or started
         */
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

            RetryPolicyRegistry retryPolicyRegistry = new DefaultRetryPolicyRegistry();
            retryPolicyRegistry.setDefault(defaultRetryPolicy);

            CapabilityContractMonitor contractMonitor = new CapabilityContractMonitor(eventBus, capabilityRegistry);

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

            DagStepScheduler scheduler =
                    new DagStepScheduler(pipeline, retryPolicyRegistry, eventBus, executor, capabilityRegistry);

            PlanRegistry planRegistry = new PlanRegistry();
            stepDefinitions.forEach(planRegistry::register);
            PlannerEngine plannerEngine = new PlannerEngine(planRegistry);
            RulePlanner rulePlanner = new RulePlanner(plannerEngine);

            CompositePlanner compositePlanner =
                    new CompositePlanner(extraPlanners, pluginManager::registeredPlanners, rulePlanner);

            SagaOrchestrator sagaOrchestrator = sagaEnabled ? new SagaOrchestrator(pipeline, eventBus, executor) : null;

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

            CronScheduler cronScheduler =
                    executionStore != null ? new CronScheduler(engine, executionStore, eventBus, tenantId) : null;

            NexoraEngine built = new NexoraEngine(
                    engine,
                    pluginManager,
                    eventBus,
                    capabilityRegistry,
                    contractMonitor,
                    cronScheduler,
                    tracer,
                    executor,
                    tenantId,
                    shutdownDrainTimeout);
            if (shutdownHookEnabled) {
                built.installShutdownHook();
            }
            return built;
        }
    }
}
