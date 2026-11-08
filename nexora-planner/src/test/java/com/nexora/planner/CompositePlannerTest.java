package com.nexora.planner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexora.core.intent.Intent;
import com.nexora.core.plan.Plan;
import com.nexora.planner.engine.CompositePlanner;
import com.nexora.planner.engine.PlannerEngine;
import com.nexora.planner.engine.RulePlanner;
import com.nexora.planner.registry.PlanRegistry;
import com.nexora.spi.CapabilityDescriptor;
import com.nexora.spi.Planner;
import com.nexora.spi.PlannerDescriptor;
import com.nexora.spi.PlanningContext;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class CompositePlannerTest {

    private static final PlanningContext CONTEXT = new PlanningContext() {
        @Override
        public List<CapabilityDescriptor> availableCapabilities() {
            return List.of();
        }

        @Override
        public Map<String, Object> config() {
            return Map.of();
        }
    };

    private final RulePlanner fallback = new RulePlanner(new PlannerEngine(new PlanRegistry()));

    @Test
    void triesPlannersInDescendingPriorityWithFallbackLast() {
        Planner low = new NamedPlanner("low", 1, false);
        Planner high = new NamedPlanner("high", 50, false);

        CompositePlanner composite = new CompositePlanner(List.of(low, high), fallback);

        assertThat(composite.planners()).containsExactly(high, low, fallback);
    }

    @Test
    void readsLivePlannersOnEveryCall() {
        Planner fixed = new NamedPlanner("fixed", 5, false);
        Planner v1 = new NamedPlanner("plugin-v1", 10, true);
        Planner v2 = new NamedPlanner("plugin-v2", 10, true);
        AtomicReference<List<Planner>> live = new AtomicReference<>(List.of(v1));
        CompositePlanner composite = new CompositePlanner(List.of(fixed), live::get, fallback);

        assertThat(composite.plan(new Intent("goal", Map.of()), CONTEXT).getSteps())
                .isEmpty();
        assertThat(composite.planners()).containsExactly(v1, fixed, fallback);

        live.set(List.of(v2));

        assertThat(composite.planners()).containsExactly(v2, fixed, fallback);
    }

    @Test
    void reusesOrderingWhileLiveListIsUnchanged() {
        List<Planner> live = List.of(new NamedPlanner("plugin", 10, false));
        CompositePlanner composite = new CompositePlanner(List.of(), () -> live, fallback);

        assertThat(composite.planners()).isSameAs(composite.planners());
    }

    @Test
    void throwsWhenLiveSupplierReturnsNull() {
        CompositePlanner composite = new CompositePlanner(List.of(), () -> null, fallback);

        assertThatThrownBy(composite::planners).isInstanceOf(NullPointerException.class);
    }

    private record NamedPlanner(String id, int priority, boolean accepts) implements Planner {
        @Override
        public PlannerDescriptor descriptor() {
            return new PlannerDescriptor(id, id, priority);
        }

        @Override
        public boolean canPlan(Intent intent, PlanningContext context) {
            return accepts;
        }

        @Override
        public Plan plan(Intent intent, PlanningContext context) {
            return new Plan(List.of());
        }
    }
}
