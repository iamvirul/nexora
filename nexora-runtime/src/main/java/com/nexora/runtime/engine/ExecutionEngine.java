package com.nexora.runtime.engine;

import com.nexora.core.context.ExecutionContext;
import com.nexora.core.context.TraceContext;
import com.nexora.core.execution.ExecutionResult;
import com.nexora.core.execution.ExecutionStatus;
import com.nexora.core.intent.Intent;
import com.nexora.core.plan.Plan;
import com.nexora.event.ExecutionDeadLetteredEvent;
import com.nexora.event.ExecutionEventBus;
import com.nexora.event.PlanCompletedEvent;
import com.nexora.event.PlanFailedEvent;
import com.nexora.event.PlanStartedEvent;
import com.nexora.event.PlanTimedOutEvent;
import com.nexora.event.StepCompletedEvent;
import com.nexora.event.StepFailedEvent;
import com.nexora.event.StepStartedEvent;
import com.nexora.persistence.DeadLetterRecord;
import com.nexora.executor.DagStepScheduler;
import com.nexora.persistence.ExecutionRecord;
import com.nexora.persistence.ExecutionState;
import com.nexora.persistence.ExecutionStore;
import com.nexora.persistence.StepRecord;
import com.nexora.planner.engine.DefaultPlanningContext;
import com.nexora.runtime.webhook.WebhookDeliveryService;
import com.nexora.saga.SagaOrchestrator;
import com.nexora.spi.CapabilityRegistry;
import com.nexora.spi.Planner;
import com.nexora.spi.PlanningContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ExecutionEngine {

    private static final Logger log = LoggerFactory.getLogger(ExecutionEngine.class);

    private final Planner planner;
    private final CapabilityRegistry capabilityRegistry;
    private final DagStepScheduler scheduler;
    private final ExecutionEventBus eventBus;
    private final ExecutionStore store;              // null = persistence disabled
    private final SagaOrchestrator sagaOrchestrator; // null = saga disabled
    private final Duration defaultPlanDeadline;      // null = no engine-wide deadline
    private final Executor executor;
    private final WebhookDeliveryService webhookDeliveryService;

    public ExecutionEngine(
            Planner planner,
            CapabilityRegistry capabilityRegistry,
            DagStepScheduler scheduler,
            ExecutionEventBus eventBus) {
        this(planner, capabilityRegistry, scheduler, eventBus, null, null, null, null, null);
    }

    public ExecutionEngine(
            Planner planner,
            CapabilityRegistry capabilityRegistry,
            DagStepScheduler scheduler,
            ExecutionEventBus eventBus,
            ExecutionStore store) {
        this(planner, capabilityRegistry, scheduler, eventBus, store, null, null, null, null);
    }

    public ExecutionEngine(
            Planner planner,
            CapabilityRegistry capabilityRegistry,
            DagStepScheduler scheduler,
            ExecutionEventBus eventBus,
            ExecutionStore store,
            SagaOrchestrator sagaOrchestrator) {
        this(planner, capabilityRegistry, scheduler, eventBus, store, sagaOrchestrator, null, null, null);
    }

    /**
     * Full constructor — includes plan deadline and the executor used to schedule the watchdog.
     *
     * @param defaultPlanDeadline engine-wide fallback deadline; {@code null} means no default
     * @param executor            the executor to schedule the delayed watchdog task on
     */
    public ExecutionEngine(
            Planner planner,
            CapabilityRegistry capabilityRegistry,
            DagStepScheduler scheduler,
            ExecutionEventBus eventBus,
            ExecutionStore store,
            SagaOrchestrator sagaOrchestrator,
            Duration defaultPlanDeadline,
            Executor executor,
            String webhookSecret) {
        this.planner = Objects.requireNonNull(planner);
        this.capabilityRegistry = Objects.requireNonNull(capabilityRegistry);
        this.scheduler = Objects.requireNonNull(scheduler);
        this.eventBus = Objects.requireNonNull(eventBus);
        this.store = store;
        this.sagaOrchestrator = sagaOrchestrator;
        this.defaultPlanDeadline = defaultPlanDeadline;
        this.executor = executor != null ? executor : Executors.newVirtualThreadPerTaskExecutor();
        this.webhookDeliveryService = new WebhookDeliveryService(webhookSecret, store, this.executor);
        if (store != null) {
            wireStoreSubscriptions();
        }
    }

    private void wireStoreSubscriptions() {
        eventBus.subscribe(StepStartedEvent.class, e -> {
            try {
                store.upsertStep(e.executionId(),
                        StepRecord.started(e.stepId(), e.capabilityId(), e.idempotencyKey(), e.occurredAt()));
            } catch (Exception ex) {
                log.warn("Persistence: failed to record step start stepId={}", e.stepId(), ex);
            }
        });
        eventBus.subscribe(StepCompletedEvent.class, e -> {
            try {
                StepRecord running = StepRecord.started(e.stepId(), e.capabilityId(), null,
                        e.occurredAt().minus(e.elapsed()));
                store.upsertStep(e.executionId(), running.completed(e.occurredAt()));
            } catch (Exception ex) {
                log.warn("Persistence: failed to record step completion stepId={}", e.stepId(), ex);
            }
        });
        eventBus.subscribe(StepFailedEvent.class, e -> {
            try {
                StepRecord running = StepRecord.started(e.stepId(), e.capabilityId(), null,
                        e.occurredAt().minus(e.elapsed()));
                store.upsertStep(e.executionId(),
                        running.failed(e.failureCode(), e.failureMessage(), e.occurredAt()));
            } catch (Exception ex) {
                log.warn("Persistence: failed to record step failure stepId={}", e.stepId(), ex);
            }
        });
    }

    private void writeDeadLetter(String executionId, Intent intent, String failureCode,
                                 String failureMessage, Instant failedAt) {
        if (store == null) return;
        String dlId = UUID.randomUUID().toString();
        DeadLetterRecord dl = DeadLetterRecord.pending(
                dlId, executionId, intent.getGoal(), intent.getContext(),
                failureCode, failureMessage, failedAt);
        try {
            store.createDeadLetter(dl);
        } catch (Exception e) {
            log.warn("Persistence: failed to create dead letter executionId={}", executionId, e);
            return;
        }
        eventBus.publish(new ExecutionDeadLetteredEvent(executionId, dlId, failureCode, failureMessage, failedAt));
        log.warn("Execution dead-lettered executionId={} deadLetterId={} code={}", executionId, dlId, failureCode);
    }

    private void persistExecutionState(String executionId, ExecutionState state, Instant completedAt) {
        if (store == null) return;
        try {
            store.updateExecution(executionId, state, completedAt);
        } catch (Exception e) {
            log.warn("Persistence: failed to update execution state executionId={}", executionId, e);
        }
    }

    public ExecutionStore getStore() {
        return store;
    }

    public CompletableFuture<ExecutionResult> execute(Intent intent) {
        TraceContext traceContext = TraceContext.root();
        ExecutionContext ctx = new ExecutionContext(intent, traceContext);

        PlanningContext planningContext = new DefaultPlanningContext(capabilityRegistry, Map.of());
        Plan plan = planner.plan(intent, planningContext);
        Instant planStart = Instant.now();

        // Per-intent deadline takes priority; fall back to engine-wide default.
        Duration effectiveDeadline = intent.getDeadline() != null
                ? intent.getDeadline()
                : defaultPlanDeadline;

        if (effectiveDeadline != null) {
            log.info("Execution deadline set executionId={} deadline={}",
                    ctx.getExecutionId(), effectiveDeadline);
        }

        // Shared flag between the watchdog (writer) and the scheduler (reader).
        AtomicBoolean halted = new AtomicBoolean(false);

        log.info("Starting execution executionId={} traceId={} goal={}",
                ctx.getExecutionId(), traceContext.traceId(), intent.getGoal());

        if (store != null) {
            try {
                store.createExecution(ExecutionRecord.started(
                        ctx.getExecutionId(), traceContext.traceId(),
                        intent.getGoal(), intent.getContext(), planStart));
            } catch (Exception e) {
                log.warn("Persistence: failed to create execution record executionId={}", ctx.getExecutionId(), e);
            }
        }

        eventBus.publish(new PlanStartedEvent(ctx.getExecutionId(), traceContext.traceId(), planStart));

        DagStepScheduler.ScheduleSession session = scheduler.schedule(plan, ctx, halted);
        CompletableFuture<ExecutionResult> scheduledFuture = session.future();

        if (effectiveDeadline != null && executor != null) {
            // Run the watchdog on the existing executor after the deadline duration.
            // CompletableFuture.delayedExecutor requires no dedicated ScheduledExecutorService.
            Executor watchdogExecutor = CompletableFuture.delayedExecutor(
                    effectiveDeadline.toMillis(), TimeUnit.MILLISECONDS, executor);

            Duration capturedDeadline = effectiveDeadline; // effectively final for lambda
            CompletableFuture.runAsync(() -> {
                // Short-circuit: if the execution already finished naturally, nothing to do.
                if (scheduledFuture.isDone()) return;

                log.warn("Plan deadline expired executionId={} deadline={}",
                        ctx.getExecutionId(), capturedDeadline);

                halted.set(true);
            }, watchdogExecutor);
        }

        return scheduledFuture.whenComplete((result, ex) -> {
            Instant now = Instant.now();
            Duration elapsed = Duration.between(planStart, now);

            if (ex != null) {
                log.error("Execution threw unexpectedly executionId={}", ctx.getExecutionId(), ex);
                persistExecutionState(ctx.getExecutionId(), ExecutionState.FAILED, now);
                eventBus.publish(new PlanFailedEvent(
                        ctx.getExecutionId(), traceContext.traceId(),
                        null, "UNEXPECTED_ERROR", elapsed, now
                ));
                writeDeadLetter(ctx.getExecutionId(), intent, "UNEXPECTED_ERROR",
                        ex.getMessage(), now);
                webhookDeliveryService.deliverIfApplicable(ctx.getExecutionId(), intent, ExecutionStatus.FAILED, elapsed);

            } else if (result.status() == ExecutionStatus.TIMED_OUT) {
                persistExecutionState(ctx.getExecutionId(), ExecutionState.TIMED_OUT, now);
                eventBus.publish(new PlanTimedOutEvent(
                        ctx.getExecutionId(), traceContext.traceId(),
                        effectiveDeadline, elapsed, now));
                log.warn("Execution timed out executionId={} elapsed={}ms deadline={}",
                        ctx.getExecutionId(), elapsed.toMillis(), effectiveDeadline);
                webhookDeliveryService.deliverIfApplicable(ctx.getExecutionId(), intent, ExecutionStatus.TIMED_OUT, elapsed);

                if (sagaOrchestrator != null) {
                    persistExecutionState(ctx.getExecutionId(), ExecutionState.COMPENSATING, now);
                    sagaOrchestrator.compensate(plan, result, ctx)
                            .whenComplete((v, err) -> {
                                if (err != null) {
                                    log.error("Saga compensation threw on timeout executionId={}",
                                            ctx.getExecutionId(), err);
                                }
                                persistExecutionState(ctx.getExecutionId(), ExecutionState.COMPENSATED, Instant.now());
                            });
                }

            } else if (result.status() == ExecutionStatus.FAILED) {
                String failedStep = result.stepResults().stream()
                        .filter(sr -> !sr.succeeded())
                        .map(sr -> sr.stepId())
                        .findFirst().orElse(null);
                String failureMessage = result.stepResults().stream()
                        .filter(sr -> !sr.succeeded())
                        .filter(sr -> sr.capabilityResult() != null)
                        .map(sr -> sr.capabilityResult().failureMessage())
                        .filter(Objects::nonNull)
                        .findFirst().orElse(null);
                persistExecutionState(ctx.getExecutionId(), ExecutionState.FAILED, now);
                eventBus.publish(new PlanFailedEvent(
                        ctx.getExecutionId(), traceContext.traceId(),
                        failedStep, "STEP_FAILED", elapsed, now
                ));
                writeDeadLetter(ctx.getExecutionId(), intent, "STEP_FAILED", failureMessage, now);
                log.warn("Execution failed executionId={} failedStep={}", ctx.getExecutionId(), failedStep);
                webhookDeliveryService.deliverIfApplicable(ctx.getExecutionId(), intent, ExecutionStatus.FAILED, elapsed);
                if (sagaOrchestrator != null) {
                    persistExecutionState(ctx.getExecutionId(), ExecutionState.COMPENSATING, now);
                    sagaOrchestrator.compensate(plan, result, ctx)
                            .whenComplete((v, err) -> {
                                if (err != null) {
                                    log.error("Saga compensation threw executionId={}", ctx.getExecutionId(), err);
                                }
                                persistExecutionState(ctx.getExecutionId(), ExecutionState.COMPENSATED, Instant.now());
                            });
                }

            } else {
                persistExecutionState(ctx.getExecutionId(), ExecutionState.COMPLETED, now);
                eventBus.publish(new PlanCompletedEvent(
                        ctx.getExecutionId(), traceContext.traceId(), elapsed, now
                ));
                log.info("Execution completed executionId={} elapsed={}ms",
                        ctx.getExecutionId(), elapsed.toMillis());
                webhookDeliveryService.deliverIfApplicable(ctx.getExecutionId(), intent, ExecutionStatus.COMPLETED, elapsed);
            }
        });
    }
}
