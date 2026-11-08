package com.nexora.planner.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexora.core.plan.Plan;
import com.nexora.core.plan.Step;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlanCacheTest {

    private static final Plan PLAN = new Plan(List.of(Step.of("a", "validate_order")));

    private final MutableClock clock = new MutableClock();

    @Test
    void returnsStoredPlanWithinTtl() {
        PlanCache cache = new PlanCache(Duration.ofMinutes(10), 10, clock);
        cache.put("k", PLAN);

        clock.advance(Duration.ofMinutes(9));

        assertThat(cache.get("k")).containsSame(PLAN);
    }

    @Test
    void expiresAfterTtl() {
        PlanCache cache = new PlanCache(Duration.ofMinutes(10), 10, clock);
        cache.put("k", PLAN);

        clock.advance(Duration.ofMinutes(10));

        assertThat(cache.get("k")).isEmpty();
        assertThat(cache.size()).isZero();
    }

    @Test
    void dropsLeastRecentlyUsedPastTheBound() {
        PlanCache cache = new PlanCache(Duration.ofMinutes(10), 2, clock);
        cache.put("a", PLAN);
        cache.put("b", PLAN);
        cache.get("a");

        cache.put("c", PLAN);

        assertThat(cache.get("a")).isPresent();
        assertThat(cache.get("b")).isEmpty();
        assertThat(cache.get("c")).isPresent();
    }

    @Test
    void zeroTtlStoresNothing() {
        PlanCache cache = new PlanCache(Duration.ZERO, 10, clock);

        cache.put("k", PLAN);

        assertThat(cache.enabled()).isFalse();
        assertThat(cache.get("k")).isEmpty();
    }

    @Test
    void keyIgnoresCapabilityAndContextOrder() {
        assertThat(PlanCache.key("goal", List.of("b", "a"), List.of("y", "x")))
                .isEqualTo(PlanCache.key("goal", List.of("a", "b"), List.of("x", "y")));
    }

    @Test
    void keyChangesWithGoalCapabilitiesOrContextNames() {
        String base = PlanCache.key("goal", List.of("a"), List.of("x"));

        assertThat(PlanCache.key("other goal", List.of("a"), List.of("x"))).isNotEqualTo(base);
        assertThat(PlanCache.key("goal", List.of("a", "b"), List.of("x"))).isNotEqualTo(base);
        assertThat(PlanCache.key("goal", List.of("a"), List.of("x", "y"))).isNotEqualTo(base);
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-08T12:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
