package com.nexora.api.observability;

import com.nexora.api.NexoraEngine;
import com.nexora.event.PlanAmendedEvent;
import com.nexora.event.PlanCompletedEvent;
import com.nexora.event.PlanFailedEvent;
import com.nexora.event.PlanStartedEvent;
import com.nexora.event.PlanTimedOutEvent;
import com.nexora.event.StepCompletedEvent;
import com.nexora.event.StepFailedEvent;
import com.nexora.event.StepStartedEvent;
import com.nexora.event.Subscription;
import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.Counter;
import io.prometheus.client.Gauge;
import io.prometheus.client.Histogram;
import io.prometheus.client.exporter.common.TextFormat;

import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bridges Nexora execution events to:
 * - Prometheus metrics exposition
 * - an in-memory process snapshot store suitable for lightweight UI rendering
 */
public final class NexoraObservability implements AutoCloseable {

    private static final int DEFAULT_MAX_EXECUTIONS = 100;
    private static final int DEFAULT_MAX_TIMELINE_EVENTS = 200;

    @FunctionalInterface
    public interface SnapshotListener {
        void onSnapshot(ProcessSnapshot snapshot);
    }

    private final CollectorRegistry registry;
    private final ProcessTracker processTracker;
    private final List<Subscription> subscriptions;
    private final List<SnapshotListener> snapshotListeners = new CopyOnWriteArrayList<>();

    private final Counter planStartedTotal;
    private final Counter planCompletedTotal;
    private final Counter planFailedTotal;
    private final Counter planAmendmentsTotal;
    private final Counter stepStartedTotal;
    private final Counter stepCompletedTotal;
    private final Counter stepFailedTotal;
    private final Histogram planDurationSeconds;
    private final Histogram stepDurationSeconds;
    private final Gauge activeExecutionsGauge;
    private final AtomicInteger activeExecutions = new AtomicInteger();

    private NexoraObservability(
            NexoraEngine engine,
            int maxExecutions,
            int maxTimelineEventsPerExecution) {
        Objects.requireNonNull(engine, "engine must not be null");
        if (maxExecutions < 1) {
            throw new IllegalArgumentException("maxExecutions must be >= 1");
        }
        if (maxTimelineEventsPerExecution < 1) {
            throw new IllegalArgumentException("maxTimelineEventsPerExecution must be >= 1");
        }

        this.registry = new CollectorRegistry(true);
        this.processTracker = new ProcessTracker(maxExecutions, maxTimelineEventsPerExecution);
        this.subscriptions = new ArrayList<>();

        this.planStartedTotal = Counter.build()
                .name("nexora_plan_started_total")
                .help("Total number of execution plans started.")
                .register(registry);
        this.planCompletedTotal = Counter.build()
                .name("nexora_plan_completed_total")
                .help("Total number of execution plans completed successfully.")
                .register(registry);
        this.planFailedTotal = Counter.build()
                .name("nexora_plan_failed_total")
                .help("Total number of execution plans that failed.")
                .register(registry);
        this.planAmendmentsTotal = Counter.build()
                .name("nexora_plan_amendments_total")
                .labelNames("amendment_type")
                .help("Total number of plan amendments grouped by amendment type.")
                .register(registry);
        this.stepStartedTotal = Counter.build()
                .name("nexora_step_started_total")
                .labelNames("capability_id")
                .help("Total number of started execution steps grouped by capability.")
                .register(registry);
        this.stepCompletedTotal = Counter.build()
                .name("nexora_step_completed_total")
                .labelNames("capability_id")
                .help("Total number of completed execution steps grouped by capability.")
                .register(registry);
        this.stepFailedTotal = Counter.build()
                .name("nexora_step_failed_total")
                .labelNames("capability_id", "failure_code")
                .help("Total number of failed execution steps grouped by capability and failure code.")
                .register(registry);
        this.planDurationSeconds = Histogram.build()
                .name("nexora_plan_duration_seconds")
                .labelNames("status")
                .buckets(0.01, 0.05, 0.1, 0.25, 0.5, 1, 2, 5, 10, 30, 60)
                .help("Execution plan duration in seconds.")
                .register(registry);
        this.stepDurationSeconds = Histogram.build()
                .name("nexora_step_duration_seconds")
                .labelNames("capability_id", "status")
                .buckets(0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2, 5, 10)
                .help("Step duration in seconds grouped by capability and terminal status.")
                .register(registry);
        this.activeExecutionsGauge = Gauge.build()
                .name("nexora_active_executions")
                .help("Current number of active executions.")
                .register(registry);

        subscriptions.add(engine.subscribe(PlanStartedEvent.class, this::onPlanStarted));
        subscriptions.add(engine.subscribe(PlanCompletedEvent.class, this::onPlanCompleted));
        subscriptions.add(engine.subscribe(PlanFailedEvent.class, this::onPlanFailed));
        subscriptions.add(engine.subscribe(PlanTimedOutEvent.class, this::onPlanTimedOut));
        subscriptions.add(engine.subscribe(PlanAmendedEvent.class, this::onPlanAmended));
        subscriptions.add(engine.subscribe(StepStartedEvent.class, this::onStepStarted));
        subscriptions.add(engine.subscribe(StepCompletedEvent.class, this::onStepCompleted));
        subscriptions.add(engine.subscribe(StepFailedEvent.class, this::onStepFailed));
    }

    public static NexoraObservability attach(NexoraEngine engine) {
        return new NexoraObservability(engine, DEFAULT_MAX_EXECUTIONS, DEFAULT_MAX_TIMELINE_EVENTS);
    }

    public static NexoraObservability attach(
            NexoraEngine engine,
            int maxExecutions,
            int maxTimelineEventsPerExecution) {
        return new NexoraObservability(engine, maxExecutions, maxTimelineEventsPerExecution);
    }

    public String metricsContentType() {
        return TextFormat.CONTENT_TYPE_004;
    }

    public String scrapePrometheus() {
        StringWriter writer = new StringWriter();
        try {
            TextFormat.write004(writer, registry.metricFamilySamples());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to render Prometheus metrics", e);
        }
        return writer.toString();
    }

    public ProcessSnapshot processSnapshot() {
        return processTracker.snapshot();
    }

    public CollectorRegistry registry() {
        return registry;
    }

    public void addSnapshotListener(SnapshotListener listener) {
        snapshotListeners.add(Objects.requireNonNull(listener));
    }

    private void broadcastSnapshot() {
        if (snapshotListeners.isEmpty()) return;
        ProcessSnapshot snap = processSnapshot();
        for (SnapshotListener listener : snapshotListeners) {
            try {
                listener.onSnapshot(snap);
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void close() {
        for (Subscription subscription : subscriptions) {
            try {
                subscription.cancel();
            } catch (Exception ignored) {
                // best-effort cleanup
            }
        }
        subscriptions.clear();
    }

    private void onPlanStarted(PlanStartedEvent event) {
        planStartedTotal.inc();
        activeExecutionsGauge.set(activeExecutions.incrementAndGet());
        processTracker.onPlanStarted(event);
        broadcastSnapshot();
    }

    private void onPlanCompleted(PlanCompletedEvent event) {
        planCompletedTotal.inc();
        planDurationSeconds.labels("completed").observe(seconds(event.elapsed().toNanos()));
        activeExecutionsGauge.set(Math.max(0, activeExecutions.decrementAndGet()));
        processTracker.onPlanCompleted(event);
        broadcastSnapshot();
    }

    private void onPlanFailed(PlanFailedEvent event) {
        planFailedTotal.inc();
        planDurationSeconds.labels("failed").observe(seconds(event.elapsed().toNanos()));
        activeExecutionsGauge.set(Math.max(0, activeExecutions.decrementAndGet()));
        processTracker.onPlanFailed(event);
        broadcastSnapshot();
    }

    private void onPlanTimedOut(PlanTimedOutEvent event) {
        planFailedTotal.inc();
        planDurationSeconds.labels("timed_out").observe(seconds(event.elapsed().toNanos()));
        activeExecutionsGauge.set(Math.max(0, activeExecutions.decrementAndGet()));
        processTracker.onPlanTimedOut(event);
        broadcastSnapshot();
    }

    private void onPlanAmended(PlanAmendedEvent event) {
        planAmendmentsTotal.labels(safeLabel(event.amendmentType())).inc();
        processTracker.onPlanAmended(event);
        broadcastSnapshot();
    }

    private void onStepStarted(StepStartedEvent event) {
        stepStartedTotal.labels(safeLabel(event.capabilityId())).inc();
        processTracker.onStepStarted(event);
        broadcastSnapshot();
    }

    private void onStepCompleted(StepCompletedEvent event) {
        stepCompletedTotal.labels(safeLabel(event.capabilityId())).inc();
        stepDurationSeconds.labels(safeLabel(event.capabilityId()), "completed")
                .observe(seconds(event.elapsed().toNanos()));
        processTracker.onStepCompleted(event);
        broadcastSnapshot();
    }

    private void onStepFailed(StepFailedEvent event) {
        String capabilityId = safeLabel(event.capabilityId());
        String failureCode = safeLabel(event.failureCode());
        String status = "SKIPPED".equalsIgnoreCase(event.failureCode()) ? "skipped" : "failed";
        stepFailedTotal.labels(capabilityId, failureCode).inc();
        stepDurationSeconds.labels(capabilityId, status).observe(seconds(event.elapsed().toNanos()));
        processTracker.onStepFailed(event);
        broadcastSnapshot();
    }

    private static String safeLabel(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        String sanitized = value.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_:.\\-]", "_");
        return sanitized.isBlank() ? "unknown" : sanitized;
    }

    private static double seconds(long nanos) {
        return nanos / 1_000_000_000.0;
    }

    public record ProcessSnapshot(
            long generatedAtMs,
            int activeExecutions,
            List<ExecutionSnapshot> executions
    ) {}

    public record ExecutionSnapshot(
            String executionId,
            String traceId,
            String status,
            long startedAtMs,
            long finishedAtMs,
            int totalSteps,
            int runningSteps,
            int completedSteps,
            int failedSteps,
            int skippedSteps,
            List<StepSnapshot> steps,
            List<TimelineSnapshot> timeline
    ) {}

    public record StepSnapshot(
            String stepId,
            String capabilityId,
            String status,
            long startedAtMs,
            long finishedAtMs,
            long elapsedMs,
            String failureCode,
            String failureMessage
    ) {}

    public record TimelineSnapshot(
            long occurredAtMs,
            String type,
            String message
    ) {}

    private static final class ProcessTracker {
        private final int maxExecutions;
        private final int maxTimelineEventsPerExecution;
        private final ConcurrentHashMap<String, MutableExecution> executions = new ConcurrentHashMap<>();
        private final Deque<String> order = new ConcurrentLinkedDeque<>();
        private final AtomicInteger activeExecutions = new AtomicInteger();
        private final Object orderLock = new Object();

        private ProcessTracker(int maxExecutions, int maxTimelineEventsPerExecution) {
            this.maxExecutions = maxExecutions;
            this.maxTimelineEventsPerExecution = maxTimelineEventsPerExecution;
        }

        void onPlanStarted(PlanStartedEvent event) {
            MutableExecution execution = new MutableExecution(
                    event.executionId(),
                    event.traceId(),
                    event.occurredAt().toEpochMilli(),
                    maxTimelineEventsPerExecution
            );
            executions.put(event.executionId(), execution);
            activeExecutions.incrementAndGet();

            synchronized (orderLock) {
                order.remove(event.executionId());
                order.addFirst(event.executionId());
                while (order.size() > maxExecutions) {
                    String toRemove = order.removeLast();
                    MutableExecution removed = executions.remove(toRemove);
                    if (removed != null && removed.isActive()) {
                        activeExecutions.updateAndGet(v -> Math.max(0, v - 1));
                    }
                }
            }
        }

        void onPlanCompleted(PlanCompletedEvent event) {
            MutableExecution execution = executions.get(event.executionId());
            if (execution == null) return;
            if (execution.markCompleted(event.occurredAt().toEpochMilli())) {
                activeExecutions.updateAndGet(v -> Math.max(0, v - 1));
            }
            execution.appendTimeline(
                    event.occurredAt().toEpochMilli(),
                    "plan_completed",
                    "Execution completed"
            );
        }

        void onPlanFailed(PlanFailedEvent event) {
            MutableExecution execution = executions.get(event.executionId());
            if (execution == null) return;
            if (execution.markFailed(event.occurredAt().toEpochMilli())) {
                activeExecutions.updateAndGet(v -> Math.max(0, v - 1));
            }
            String message = event.failedStepId() == null
                    ? "Execution failed"
                    : "Execution failed on step " + event.failedStepId();
            execution.appendTimeline(event.occurredAt().toEpochMilli(), "plan_failed", message);
        }

        void onPlanTimedOut(PlanTimedOutEvent event) {
            MutableExecution execution = executions.get(event.executionId());
            if (execution == null) return;
            if (execution.markTimedOut(event.occurredAt().toEpochMilli())) {
                activeExecutions.updateAndGet(v -> Math.max(0, v - 1));
            }
            execution.appendTimeline(event.occurredAt().toEpochMilli(), "plan_timed_out", "Execution timed out (exceeded deadline)");
        }

        void onPlanAmended(PlanAmendedEvent event) {
            MutableExecution execution = executions.get(event.executionId());
            if (execution == null) return;
            execution.appendTimeline(
                    event.timestamp().toEpochMilli(),
                    "plan_amended",
                    event.amendmentType() + " -> " + event.targetStepId()
            );
        }

        void onStepStarted(StepStartedEvent event) {
            MutableExecution execution = executions.get(event.executionId());
            if (execution == null) return;
            execution.markStepStarted(
                    event.stepId(),
                    event.capabilityId(),
                    event.occurredAt().toEpochMilli()
            );
        }

        void onStepCompleted(StepCompletedEvent event) {
            MutableExecution execution = executions.get(event.executionId());
            if (execution == null) return;
            execution.markStepCompleted(
                    event.stepId(),
                    event.capabilityId(),
                    event.occurredAt().toEpochMilli(),
                    event.elapsed().toMillis()
            );
        }

        void onStepFailed(StepFailedEvent event) {
            MutableExecution execution = executions.get(event.executionId());
            if (execution == null) return;
            execution.markStepFailed(
                    event.stepId(),
                    event.capabilityId(),
                    event.occurredAt().toEpochMilli(),
                    event.elapsed().toMillis(),
                    event.failureCode(),
                    event.failureMessage()
            );
        }

        ProcessSnapshot snapshot() {
            List<String> ids;
            synchronized (orderLock) {
                ids = new ArrayList<>(order);
            }

            List<ExecutionSnapshot> executionViews = new ArrayList<>(ids.size());
            for (String id : ids) {
                MutableExecution execution = executions.get(id);
                if (execution != null) {
                    executionViews.add(execution.toSnapshot());
                }
            }
            return new ProcessSnapshot(
                    System.currentTimeMillis(),
                    Math.max(0, activeExecutions.get()),
                    List.copyOf(executionViews)
            );
        }
    }

    private static final class MutableExecution {
        private static final String STATUS_RUNNING = "RUNNING";
        private static final String STATUS_COMPLETED = "COMPLETED";
        private static final String STATUS_FAILED = "FAILED";
        private static final String STATUS_TIMED_OUT = "TIMED_OUT";

        private final String executionId;
        private final Deque<TimelineSnapshot> timeline = new ConcurrentLinkedDeque<>();
        private final ConcurrentHashMap<String, MutableStep> steps = new ConcurrentHashMap<>();
        private final int maxTimelineEvents;

        private volatile String traceId;
        private volatile String status;
        private volatile long startedAtMs;
        private volatile long finishedAtMs;

        private MutableExecution(
                String executionId,
                String traceId,
                long startedAtMs,
                int maxTimelineEvents) {
            this.executionId = executionId;
            this.traceId = traceId;
            this.startedAtMs = startedAtMs;
            this.maxTimelineEvents = maxTimelineEvents;
            this.status = STATUS_RUNNING;
            appendTimeline(startedAtMs, "plan_started", "Execution started");
        }

        private synchronized void markStepStarted(String stepId, String capabilityId, long occurredAtMs) {
            MutableStep step = steps.computeIfAbsent(stepId, id -> new MutableStep(stepId));
            step.markStarted(capabilityId, occurredAtMs);
            appendTimeline(occurredAtMs, "step_started", stepId + " (" + capabilityId + ")");
        }

        private synchronized void markStepCompleted(
                String stepId,
                String capabilityId,
                long occurredAtMs,
                long elapsedMs) {
            MutableStep step = steps.computeIfAbsent(stepId, id -> new MutableStep(stepId));
            step.markCompleted(capabilityId, occurredAtMs, elapsedMs);
            appendTimeline(occurredAtMs, "step_completed", stepId + " (" + elapsedMs + "ms)");
        }

        private synchronized void markStepFailed(
                String stepId,
                String capabilityId,
                long occurredAtMs,
                long elapsedMs,
                String failureCode,
                String failureMessage) {
            MutableStep step = steps.computeIfAbsent(stepId, id -> new MutableStep(stepId));
            step.markFailed(capabilityId, occurredAtMs, elapsedMs, failureCode, failureMessage);
            appendTimeline(
                    occurredAtMs,
                    "step_failed",
                    stepId + " [" + (failureCode == null ? "UNKNOWN" : failureCode) + "]"
            );
        }

        private synchronized boolean markCompleted(long finishedAtMs) {
            if (!STATUS_RUNNING.equals(status)) return false;
            this.status = STATUS_COMPLETED;
            this.finishedAtMs = finishedAtMs;
            return true;
        }

        private synchronized boolean markFailed(long finishedAtMs) {
            if (!STATUS_RUNNING.equals(status)) return false;
            this.status = STATUS_FAILED;
            this.finishedAtMs = finishedAtMs;
            return true;
        }

        private synchronized boolean markTimedOut(long finishedAtMs) {
            if (!STATUS_RUNNING.equals(status)) return false;
            this.status = STATUS_TIMED_OUT;
            this.finishedAtMs = finishedAtMs;
            return true;
        }

        private synchronized boolean isActive() {
            return STATUS_RUNNING.equals(status);
        }

        private synchronized void appendTimeline(long occurredAtMs, String type, String message) {
            timeline.addLast(new TimelineSnapshot(occurredAtMs, type, message));
            while (timeline.size() > maxTimelineEvents) {
                timeline.removeFirst();
            }
        }

        private synchronized ExecutionSnapshot toSnapshot() {
            List<StepSnapshot> stepSnapshots = steps.values().stream()
                    .map(MutableStep::toSnapshot)
                    .sorted(Comparator
                            .comparingLong(StepSnapshot::startedAtMs)
                            .thenComparing(StepSnapshot::stepId))
                    .toList();

            int running = 0;
            int completed = 0;
            int failed = 0;
            int skipped = 0;
            for (StepSnapshot step : stepSnapshots) {
                switch (step.status()) {
                    case "RUNNING" -> running++;
                    case "COMPLETED" -> completed++;
                    case "SKIPPED" -> skipped++;
                    case "FAILED" -> failed++;
                    default -> { }
                }
            }

            List<TimelineSnapshot> timelineSnapshots = List.copyOf(timeline);

            return new ExecutionSnapshot(
                    executionId,
                    traceId,
                    status,
                    startedAtMs,
                    finishedAtMs,
                    stepSnapshots.size(),
                    running,
                    completed,
                    failed,
                    skipped,
                    stepSnapshots,
                    timelineSnapshots
            );
        }
    }

    private static final class MutableStep {
        private static final String STATUS_PENDING = "PENDING";
        private static final String STATUS_RUNNING = "RUNNING";
        private static final String STATUS_COMPLETED = "COMPLETED";
        private static final String STATUS_FAILED = "FAILED";
        private static final String STATUS_SKIPPED = "SKIPPED";

        private final String stepId;

        private volatile String capabilityId = "unknown";
        private volatile String status = STATUS_PENDING;
        private volatile long startedAtMs;
        private volatile long finishedAtMs;
        private volatile long elapsedMs;
        private volatile String failureCode;
        private volatile String failureMessage;

        private MutableStep(String stepId) {
            this.stepId = stepId;
        }

        private synchronized void markStarted(String capabilityId, long startedAtMs) {
            this.capabilityId = capabilityId == null ? "unknown" : capabilityId;
            this.status = STATUS_RUNNING;
            this.startedAtMs = startedAtMs;
            this.finishedAtMs = 0;
            this.elapsedMs = 0;
            this.failureCode = null;
            this.failureMessage = null;
        }

        private synchronized void markCompleted(String capabilityId, long finishedAtMs, long elapsedMs) {
            this.capabilityId = capabilityId == null ? this.capabilityId : capabilityId;
            this.status = STATUS_COMPLETED;
            if (startedAtMs == 0) {
                startedAtMs = Math.max(0, finishedAtMs - elapsedMs);
            }
            this.finishedAtMs = finishedAtMs;
            this.elapsedMs = elapsedMs;
            this.failureCode = null;
            this.failureMessage = null;
        }

        private synchronized void markFailed(
                String capabilityId,
                long finishedAtMs,
                long elapsedMs,
                String failureCode,
                String failureMessage) {
            this.capabilityId = capabilityId == null ? this.capabilityId : capabilityId;
            this.status = "SKIPPED".equalsIgnoreCase(failureCode) ? STATUS_SKIPPED : STATUS_FAILED;
            if (startedAtMs == 0) {
                startedAtMs = Math.max(0, finishedAtMs - elapsedMs);
            }
            this.finishedAtMs = finishedAtMs;
            this.elapsedMs = elapsedMs;
            this.failureCode = failureCode;
            this.failureMessage = failureMessage;
        }

        private synchronized StepSnapshot toSnapshot() {
            return new StepSnapshot(
                    stepId,
                    capabilityId,
                    status,
                    startedAtMs,
                    finishedAtMs,
                    elapsedMs,
                    failureCode,
                    failureMessage
            );
        }
    }
}
