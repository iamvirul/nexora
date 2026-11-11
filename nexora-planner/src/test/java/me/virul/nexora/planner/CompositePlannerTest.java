package me.virul.nexora.planner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import me.virul.nexora.core.intent.Intent;
import me.virul.nexora.core.plan.Plan;
import me.virul.nexora.core.plan.Step;
import me.virul.nexora.planner.engine.CompositePlanner;
import me.virul.nexora.planner.engine.PlannerEngine;
import me.virul.nexora.planner.engine.RulePlanner;
import me.virul.nexora.planner.registry.PlanRegistry;
import me.virul.nexora.spi.CapabilityDescriptor;
import me.virul.nexora.spi.Planner;
import me.virul.nexora.spi.PlannerDescriptor;
import me.virul.nexora.spi.PlanningContext;
import me.virul.nexora.spi.PlanningDeclinedException;
import me.virul.nexora.spi.PlanningException;
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

    @Test
    void declinedPlannerFallsThroughToTheNextPlanner() {
        Planner declining = new DecliningPlanner();
        // Returns a real step, so the test fails if the composite skipped it for the (empty) fallback.
        Planner next = new Planner() {
            @Override
            public PlannerDescriptor descriptor() {
                return new PlannerDescriptor("next", "next", 10);
            }

            @Override
            public boolean canPlan(Intent intent, PlanningContext context) {
                return true;
            }

            @Override
            public Plan plan(Intent intent, PlanningContext context) {
                return new Plan(List.of(Step.of("from-next", "cap")));
            }
        };

        CompositePlanner composite = new CompositePlanner(List.of(declining, next), fallback);

        assertThat(composite.plan(new Intent("goal", Map.of()), CONTEXT).getSteps())
                .extracting(Step::id)
                .containsExactly("from-next");
    }

    @Test
    void declinedPlannerFallsBackToRulePlanner() {
        CompositePlanner composite = new CompositePlanner(List.of(new DecliningPlanner()), fallback);

        assertThat(composite.plan(new Intent("goal", Map.of()), CONTEXT).getSteps())
                .isEmpty();
    }

    @Test
    void otherPlanningFailuresStillPropagate() {
        CompositePlanner composite = new CompositePlanner(List.of(new FailingPlanner()), fallback);

        assertThatThrownBy(() -> composite.plan(new Intent("goal", Map.of()), CONTEXT))
                .isInstanceOf(PlanningException.class)
                .isNotInstanceOf(PlanningDeclinedException.class)
                .hasMessage("broken");
    }

    private static final class DecliningPlanner implements Planner {
        @Override
        public PlannerDescriptor descriptor() {
            return new PlannerDescriptor("declining", "declines", 100);
        }

        @Override
        public boolean canPlan(Intent intent, PlanningContext context) {
            return true;
        }

        @Override
        public Plan plan(Intent intent, PlanningContext context) {
            throw new PlanningDeclinedException("model returned an invalid plan");
        }
    }

    private static final class FailingPlanner implements Planner {
        @Override
        public PlannerDescriptor descriptor() {
            return new PlannerDescriptor("failing", "fails", 100);
        }

        @Override
        public boolean canPlan(Intent intent, PlanningContext context) {
            return true;
        }

        @Override
        public Plan plan(Intent intent, PlanningContext context) {
            throw new PlanningException("broken");
        }
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
