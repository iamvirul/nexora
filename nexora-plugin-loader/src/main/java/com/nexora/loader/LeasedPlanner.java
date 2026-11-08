package com.nexora.loader;

import com.nexora.core.intent.Intent;
import com.nexora.core.plan.Plan;
import com.nexora.spi.Planner;
import com.nexora.spi.PlannerDescriptor;
import com.nexora.spi.PlanningContext;
import com.nexora.spi.PlanningException;
import java.util.Objects;

/**
 * A planner from a JAR plugin that holds a lease on its plugin version while it runs. A released
 * version declines every intent, so the composite planner moves on to the next planner.
 */
final class LeasedPlanner implements Planner {

    private final Planner delegate;
    private final InvocationLeases leases;

    LeasedPlanner(Planner delegate, InvocationLeases leases) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.leases = Objects.requireNonNull(leases, "leases must not be null");
    }

    @Override
    public PlannerDescriptor descriptor() {
        return delegate.descriptor();
    }

    @Override
    public boolean canPlan(Intent intent, PlanningContext context) {
        if (!leases.tryAcquire()) {
            return false;
        }
        try {
            return delegate.canPlan(intent, context);
        } finally {
            leases.release();
        }
    }

    @Override
    public Plan plan(Intent intent, PlanningContext context) {
        if (!leases.tryAcquire()) {
            throw new PlanningException("Planner " + delegate.descriptor().id()
                    + " belongs to a plugin version that was reloaded; retry the intent");
        }
        try {
            return delegate.plan(intent, context);
        } finally {
            leases.release();
        }
    }
}
