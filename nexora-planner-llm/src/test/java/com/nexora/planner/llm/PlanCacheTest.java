package com.nexora.planner.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexora.core.plan.Plan;
import com.nexora.core.plan.Step;
import com.nexora.spi.CapabilityDescriptor;
import com.nexora.spi.ParameterDescriptor;
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

    @Test
    void signatureChangesWithTheCapabilityContract() {
        CapabilityDescriptor original =
                capability(List.of(new ParameterDescriptor("orderId", "string", true, null)), List.of());
        CapabilityDescriptor newRequiredInput = capability(
                List.of(
                        new ParameterDescriptor("orderId", "string", true, null),
                        new ParameterDescriptor("currency", "string", true, null)),
                List.of());
        CapabilityDescriptor inputNowOptional =
                capability(List.of(new ParameterDescriptor("orderId", "string", false, null)), List.of());
        CapabilityDescriptor renamedOutput = capability(
                List.of(new ParameterDescriptor("orderId", "string", true, null)),
                List.of(new ParameterDescriptor("chargeId", "string", true, null)));

        assertThat(List.of(newRequiredInput, inputNowOptional, renamedOutput))
                .extracting(PlanCache::signature)
                .doesNotContain(PlanCache.signature(original))
                .doesNotHaveDuplicates();
    }

    @Test
    void signatureIgnoresParameterOrder() {
        ParameterDescriptor a = new ParameterDescriptor("a", "string", true, null);
        ParameterDescriptor b = new ParameterDescriptor("b", "string", false, null);

        assertThat(PlanCache.signature(capability(List.of(a, b), List.of())))
                .isEqualTo(PlanCache.signature(capability(List.of(b, a), List.of())));
    }

    private static CapabilityDescriptor capability(
            List<ParameterDescriptor> inputs, List<ParameterDescriptor> outputs) {
        return new CapabilityDescriptor("charge_card", "Charges", inputs, outputs, true, false);
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
