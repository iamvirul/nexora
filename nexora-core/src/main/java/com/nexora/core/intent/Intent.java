package com.nexora.core.intent;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

public class Intent {

    private final String goal;
    private final Map<String, Object> context;
    private final Duration deadline; // null = use engine default (or no deadline)

    /**
     * Backward-compatible constructor — no per-intent deadline.
     * The engine-wide default (if configured) will apply.
     */
    public Intent(String goal, Map<String, Object> context) {
        this(goal, context, null);
    }

    /**
     * Creates an Intent with an explicit per-intent deadline.
     *
     * @param deadline wall-clock limit for the entire plan execution;
     *                 {@code null} means "use the engine-wide default (or no deadline)".
     * @throws IllegalArgumentException if deadline is non-null and not positive
     */
    public Intent(String goal, Map<String, Object> context, Duration deadline) {
        this.goal = Objects.requireNonNull(goal, "goal must not be null");
        this.context = context == null ? Map.of() : Map.copyOf(context);
        if (deadline != null && !deadline.isPositive()) {
            throw new IllegalArgumentException(
                    "deadline must be a positive duration, got: " + deadline);
        }
        this.deadline = deadline;
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
}
