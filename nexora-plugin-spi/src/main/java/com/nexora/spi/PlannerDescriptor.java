package com.nexora.spi;

import java.util.Objects;

public record PlannerDescriptor(String id, String description, int priority) {
    public PlannerDescriptor {
        Objects.requireNonNull(id, "id must not be null");
    }

    /** Higher priority planners are tried first. */
    public PlannerDescriptor(String id, String description) {
        this(id, description, 0);
    }
}
