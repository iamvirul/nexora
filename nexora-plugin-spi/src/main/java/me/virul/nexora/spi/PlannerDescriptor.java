package me.virul.nexora.spi;

import java.util.Objects;

/**
 * Identifies a planner.
 *
 * @param id          the planner id, used in logs and plan explanations
 * @param description what the planner does, in plain language
 * @param priority    the order planners are tried in, highest first; the built-in rule planner is
 *                    always tried last
 */
public record PlannerDescriptor(String id, String description, int priority) {

    /**
     * Validates the descriptor.
     *
     * @throws NullPointerException if {@code id} is null
     */
    public PlannerDescriptor {
        Objects.requireNonNull(id, "id must not be null");
    }

    /**
     * A descriptor with priority {@code 0}. Higher priority planners are tried first.
     *
     * @param id          the planner id
     * @param description what the planner does
     */
    public PlannerDescriptor(String id, String description) {
        this(id, description, 0);
    }
}
