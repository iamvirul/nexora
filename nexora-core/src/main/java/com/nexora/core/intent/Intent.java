package com.nexora.core.intent;

import com.nexora.core.execution.ExecutionStatus;
import com.nexora.core.tenant.TenantId;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class Intent {

    private final String goal;
    private final Map<String, Object> context;
    private final Duration deadline; // null = use engine default (or no deadline)
    private final String webhookUrl;
    private final List<ExecutionStatus> webhookEvents;
    private final String tenantId;

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
    public Intent(
            String goal,
            Map<String, Object> context,
            Duration deadline,
            String webhookUrl,
            List<ExecutionStatus> webhookEvents) {
        this(goal, context, deadline, webhookUrl, webhookEvents, null);
    }

    /**
     * Creates an Intent owned by {@code tenantId}.
     *
     * @param tenantId the tenant the execution belongs to; {@code null} means {@link TenantId#DEFAULT}
     * @throws IllegalArgumentException if tenantId is not a valid tenant id
     */
    public Intent(
            String goal,
            Map<String, Object> context,
            Duration deadline,
            String webhookUrl,
            List<ExecutionStatus> webhookEvents,
            String tenantId) {
        this.tenantId = TenantId.orDefault(tenantId);
        this.goal = Objects.requireNonNull(goal, "goal must not be null");
        this.context = context == null ? Map.of() : Map.copyOf(context);
        if (deadline != null && deadline.compareTo(Duration.ZERO) <= 0) {
            throw new IllegalArgumentException("deadline must be a positive duration, got: " + deadline);
        }
        this.deadline = deadline;
        this.webhookUrl = (webhookUrl == null || webhookUrl.trim().isEmpty()) ? null : webhookUrl;
        this.webhookEvents = webhookEvents == null || webhookEvents.isEmpty()
                ? List.of(
                        ExecutionStatus.COMPLETED,
                        ExecutionStatus.FAILED,
                        ExecutionStatus.TIMED_OUT,
                        ExecutionStatus.CANCELLED)
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

    /** The tenant this intent runs for; {@link TenantId#DEFAULT} when none was given. Never null. */
    public String getTenantId() {
        return tenantId;
    }

    /** A copy of this intent owned by {@code tenantId}. */
    public Intent withTenantId(String tenantId) {
        return new Intent(goal, context, deadline, webhookUrl, webhookEvents, tenantId);
    }
}
