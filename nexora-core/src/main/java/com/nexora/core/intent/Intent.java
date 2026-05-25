package com.nexora.core.intent;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.nexora.core.execution.ExecutionStatus;

public class Intent {

    private final String goal;
    private final Map<String, Object> context;
    private final Duration deadline; // null = use engine default (or no deadline)
    private final String webhookUrl;
    private final List<ExecutionStatus> webhookEvents;

    /**
     * Backward-compatible constructor — no per-intent deadline.
     * The engine-wide default (if configured) will apply.
     */
    public Intent(String goal, Map<String, Object> context) {
        this(goal, context, null, null, null);
    }

    /**
     * Creates an Intent with an explicit per-intent deadline.
     *
     * @param deadline wall-clock limit for the entire plan execution;
     *                 {@code null} means "use the engine-wide default (or no deadline)".
     * @throws IllegalArgumentException if deadline is non-null and not positive
     */
    public Intent(String goal, Map<String, Object> context, Duration deadline) {
        this(goal, context, deadline, null, null);
    }

    /**
     * Creates an Intent with an explicit per-intent deadline and webhook configuration.
     */
    public Intent(String goal, Map<String, Object> context, Duration deadline, String webhookUrl, List<ExecutionStatus> webhookEvents) {
        this.goal = Objects.requireNonNull(goal, "goal must not be null");
        this.context = context == null ? Map.of() : Map.copyOf(context);
        if (deadline != null && deadline.compareTo(Duration.ZERO) <= 0) {
            throw new IllegalArgumentException(
                    "deadline must be a positive duration, got: " + deadline);
        }
        this.deadline = deadline;
        this.webhookUrl = webhookUrl;
        this.webhookEvents = webhookEvents == null || webhookEvents.isEmpty() 
                ? List.of(ExecutionStatus.COMPLETED, ExecutionStatus.FAILED, ExecutionStatus.TIMED_OUT)
                : List.copyOf(webhookEvents);
    }

    public String getGoal() {
        return goal;
    }

    public Map<String, Object> getContext() {
        return context;
    }

    /**
     * Returns the per-intent deadline, or {@code null} if the engine-wide default should apply.
     */
    public Duration getDeadline() {
        return deadline;
    }

    public String getWebhookUrl() {
        return webhookUrl;
    }

    public List<ExecutionStatus> getWebhookEvents() {
        return webhookEvents;
    }
}
