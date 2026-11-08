package com.nexora.planner.engine;

import com.nexora.core.intent.Intent;
import com.nexora.core.plan.Plan;
import com.nexora.spi.Planner;
import com.nexora.spi.PlannerDescriptor;
import com.nexora.spi.PlanningContext;
import com.nexora.spi.PlanningDeclinedException;
import com.nexora.spi.PlanningException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Delegates planning to the highest-priority registered planner that accepts the intent.
 *
 * Plugin planners are sorted by PlannerDescriptor.priority (descending) and tried in order.
 * The built-in RulePlanner is always appended last as the fallback — it always returns
 * canPlan()=true, so at least one planner will always match.
 *
 * <p>Planners can be fixed at construction or read live from a supplier, so plugins activated or
 * reloaded on a running engine take part in planning at once. The supplier must return the same
 * list instance until its contents change; the priority order is recomputed only when it does not.
 */
public final class CompositePlanner implements Planner {

    private static final Logger log = LoggerFactory.getLogger(CompositePlanner.class);

    private static final PlannerDescriptor DESCRIPTOR =
            new PlannerDescriptor("composite-planner", "Priority-ordered composite planner");

    private final List<Planner> fixedPlanners;
    private final Supplier<List<Planner>> livePlanners;
    private final RulePlanner fallback;
    private volatile Ordering ordering;

    /** The live list a given ordering was computed from, compared by identity. */
    private record Ordering(List<Planner> source, List<Planner> ordered) {}

    public CompositePlanner(List<Planner> pluginPlanners, RulePlanner fallback) {
        this(pluginPlanners, List::of, fallback);
    }

    /**
     * @param fixedPlanners planners that never change, such as those passed to the engine builder
     * @param livePlanners  planners read on every plan, such as those of the currently active plugins
     */
    public CompositePlanner(List<Planner> fixedPlanners, Supplier<List<Planner>> livePlanners, RulePlanner fallback) {
        this.fixedPlanners = List.copyOf(Objects.requireNonNull(fixedPlanners));
        this.livePlanners = Objects.requireNonNull(livePlanners);
        this.fallback = Objects.requireNonNull(fallback);
    }

    @Override
    public PlannerDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public boolean canPlan(Intent intent, PlanningContext context) {
        return true;
    }

    /**
     * Plans with the first planner that accepts the intent. A planner that accepts it but then
     * throws {@link PlanningDeclinedException} is skipped and the next one is tried, so the fallback
     * planner still runs; any other exception fails planning.
     */
    @Override
    public Plan plan(Intent intent, PlanningContext context) {
        for (Planner planner : planners()) {
            if (!planner.canPlan(intent, context)) {
                continue;
            }
            try {
                return planner.plan(intent, context);
            } catch (PlanningDeclinedException e) {
                log.info(
                        "Planner declined, trying the next one plannerId={} reason={}",
                        planner.descriptor().id(),
                        e.getMessage());
            }
        }
        throw new PlanningException("No planner could handle intent: " + intent.getGoal());
    }

    /** The planners in the order they are tried, ending with the fallback. */
    public List<Planner> planners() {
        List<Planner> live = Objects.requireNonNull(livePlanners.get(), "livePlanners supplied null");
        Ordering current = ordering;
        if (current != null && current.source() == live) {
            return current.ordered();
        }
        List<Planner> ordered = new ArrayList<>(fixedPlanners.size() + live.size() + 1);
        ordered.addAll(fixedPlanners);
        ordered.addAll(live);
        ordered.sort(Comparator.comparingInt(p -> -p.descriptor().priority()));
        ordered.add(fallback);
        Ordering computed = new Ordering(live, List.copyOf(ordered));
        ordering = computed;
        return computed.ordered();
    }
}
