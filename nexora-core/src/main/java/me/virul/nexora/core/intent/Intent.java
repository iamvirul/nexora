package me.virul.nexora.core.intent;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import me.virul.nexora.core.execution.ExecutionStatus;
import me.virul.nexora.core.tenant.TenantId;

/**
 * What a caller asks the engine to achieve: a goal, the context to achieve it with, and how the
 * execution is bounded, reported and owned. Immutable.
 */
public class Intent {

    private final String goal;
    private final Map<String, Object> context;
    private final Duration deadline;
    private final String webhookUrl;
    private final List<ExecutionStatus> webhookEvents;
    private final String tenantId;

    /**
     * Creates an intent with no per-intent deadline; the engine-wide default, if configured, applies.
     *
     * @param goal    the goal the planner matches against
     * @param context the values steps can read, or {@code null} for none
     * @throws NullPointerException if {@code goal} is null
     */
    public Intent(String goal, Map<String, Object> context) {
        this(goal, context, null, null, null);
    }

    /**
     * Creates an Intent with an explicit per-intent deadline.
     *
     * @param goal     the goal the planner matches against
     * @param context  the values steps can read, or {@code null} for none
     * @param deadline wall-clock limit for the entire plan execution;
     *                 {@code null} means "use the engine-wide default (or no deadline)".
     * @throws NullPointerException     if {@code goal} is null
     * @throws IllegalArgumentException if deadline is non-null and not positive
     */
    public Intent(String goal, Map<String, Object> context, Duration deadline) {
        this(goal, context, deadline, null, null);
    }

    /**
     * Creates an Intent with an explicit per-intent deadline and webhook configuration.
     *
     * @param goal          the goal the planner matches against
     * @param context       the values steps can read, or {@code null} for none
     * @param deadline      wall-clock limit for the entire plan execution, or {@code null} for the
     *                      engine-wide default
     * @param webhookUrl    the URL notified when the execution reaches one of {@code webhookEvents};
     *                      {@code null} or blank for none
     * @param webhookEvents the statuses that trigger the webhook; {@code null} or empty means every
     *                      final status
     * @throws NullPointerException     if {@code goal} is null
     * @throws IllegalArgumentException if deadline is non-null and not positive
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
     * @param goal          the goal the planner matches against
     * @param context       the values steps can read, or {@code null} for none
     * @param deadline      wall-clock limit for the entire plan execution, or {@code null} for the
     *                      engine-wide default
     * @param webhookUrl    the URL notified when the execution reaches one of {@code webhookEvents};
     *                      {@code null} or blank for none
     * @param webhookEvents the statuses that trigger the webhook; {@code null} or empty means every
     *                      final status
     * @param tenantId the tenant the execution belongs to; {@code null} means {@link TenantId#DEFAULT}
     * @throws NullPointerException     if {@code goal} is null
     * @throws IllegalArgumentException if tenantId is not a valid tenant id, or deadline is non-null
     *                                  and not positive
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

    /**
     * The goal the planner matches against.
     *
     * @return the goal; never null
     */
    public String getGoal() {
        return goal;
    }

    /**
     * The values steps can read, through {@code intent.context.<key>} bindings.
     *
     * @return the context; never null and immutable
     */
    public Map<String, Object> getContext() {
        return context;
    }

    /**
     * Returns the per-intent deadline, or {@code null} if the engine-wide default should apply.
     *
     * @return the deadline, or {@code null}
     */
    public Duration getDeadline() {
        return deadline;
    }

    /**
     * The URL notified when the execution reaches one of {@link #getWebhookEvents()}.
     *
     * @return the URL, or {@code null} when no webhook is configured
     */
    public String getWebhookUrl() {
        return webhookUrl;
    }

    /**
     * The statuses that trigger the webhook.
     *
     * @return the statuses; every final status when none were given. Never null and immutable
     */
    public List<ExecutionStatus> getWebhookEvents() {
        return webhookEvents;
    }

    /**
     * The tenant this intent runs for.
     *
     * @return the tenant id; {@link TenantId#DEFAULT} when none was given. Never null
     */
    public String getTenantId() {
        return tenantId;
    }

    /**
     * A copy of this intent owned by {@code tenantId}.
     *
     * @param tenantId the tenant of the copy; {@code null} means {@link TenantId#DEFAULT}
     * @return the copy; every other property is unchanged
     * @throws IllegalArgumentException if tenantId is not a valid tenant id
     */
    public Intent withTenantId(String tenantId) {
        return new Intent(goal, context, deadline, webhookUrl, webhookEvents, tenantId);
    }
}
