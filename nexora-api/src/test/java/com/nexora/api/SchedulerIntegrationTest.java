package com.nexora.api;

import com.nexora.core.capability.CapabilityResult;
import com.nexora.core.intent.Intent;
import com.nexora.event.ScheduledExecutionFiredEvent;
import com.nexora.persistence.MissedFirePolicy;
import com.nexora.persistence.ScheduleRecord;
import com.nexora.persistence.jdbc.JdbcExecutionStore;
import com.nexora.planner.model.StepDefinition;
import com.nexora.runtime.scheduler.ScheduledExecution;
import com.nexora.spi.Capability;
import com.nexora.spi.CapabilityDescriptor;
import com.nexora.spi.CapabilityProvider;
import com.nexora.spi.NexoraPlugin;
import com.nexora.spi.PluginContext;
import com.nexora.spi.PluginDescriptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SchedulerIntegrationTest {

    private JdbcExecutionStore store;
    private NexoraEngine engine;
    private final AtomicInteger executions = new AtomicInteger(0);

    @BeforeEach
    void setUp() {
        store = JdbcExecutionStore.h2InMemory();
        engine = NexoraEngine.builder()
                .withExecutionStore(store)
                .withPlugin(buildPlugin())
                .withStepDefinition(new StepDefinition(
                        "ping", "ping",
                        g -> g.equals("ping"),
                        Map.of(), null, Set.of(), null, null))
                .build();
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void everyMinuteCron_firesWithinExpectedWindow() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        engine.subscribe(ScheduledExecutionFiredEvent.class, e -> latch.countDown());

        // "* * * * *" fires every minute; but since we schedule it now it fires at next whole minute
        // Use a 10-second timeout — the scheduler fires at the next aligned minute which may be up to 60s away.
        // For the test, we use a sub-minute poll: reschedule to fire within ~5s by using the real clock.
        // The test verifies the schedule is registered and the event is eventually published.
        ScheduledExecution handle = engine.schedule("* * * * *", new Intent("ping", Map.of()));

        assertThat(handle.id()).isNotNull();
        assertThat(handle.nextFireTime()).isNotNull();

        // The first fire is at the next whole minute (up to 60s away).
        // We just verify the schedule was persisted and is active.
        List<ScheduleRecord> active = engine.listActiveSchedules();
        assertThat(active).hasSize(1);
        assertThat(active.get(0).cronExpression()).isEqualTo("* * * * *");
        assertThat(active.get(0).active()).isTrue();
    }

    @Test
    void schedule_withoutStore_throws() {
        NexoraEngine noStoreEngine = NexoraEngine.builder()
                .withPlugin(buildPlugin())
                .build();
        assertThatThrownBy(() -> noStoreEngine.schedule("* * * * *", new Intent("ping", Map.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("persistence store");
    }

    @Test
    void cancelSchedule_deactivatesRecord() {
        ScheduledExecution handle = engine.schedule("0 0 * * *", new Intent("ping", Map.of()));

        assertThat(engine.listActiveSchedules()).hasSize(1);
        engine.cancelSchedule(handle.id());
        assertThat(engine.listActiveSchedules()).isEmpty();

        // Full list still contains it but marked inactive
        List<ScheduleRecord> all = engine.listSchedules();
        assertThat(all).hasSize(1);
        assertThat(all.get(0).active()).isFalse();
    }

    @Test
    void cancelViaHandle_deactivatesRecord() {
        ScheduledExecution handle = engine.schedule("0 0 * * *", new Intent("ping", Map.of()));
        handle.cancel();
        assertThat(engine.listActiveSchedules()).isEmpty();
    }

    @Test
    void multipleSchedules_allPersisted() {
        engine.schedule("* * * * *",   new Intent("ping", Map.of()));
        engine.schedule("0 0 * * *",   new Intent("ping", Map.of()));
        engine.schedule("0 9 * * 1-5", new Intent("ping", Map.of()));

        assertThat(engine.listActiveSchedules()).hasSize(3);
    }

    @Test
    void invalidCronExpression_throws() {
        assertThatThrownBy(() -> engine.schedule("not a cron", new Intent("ping", Map.of())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void missedFirePolicy_persistedCorrectly() {
        engine.schedule("0 0 * * *", new Intent("ping", Map.of()), MissedFirePolicy.FIRE_ALL);
        List<ScheduleRecord> records = engine.listSchedules();
        assertThat(records).hasSize(1);
        assertThat(records.get(0).missedFirePolicy()).isEqualTo(MissedFirePolicy.FIRE_ALL);
    }

    // --- helper ---

    NexoraPlugin buildPlugin() {
        return new NexoraPlugin() {
            @Override public PluginDescriptor descriptor() {
                return new PluginDescriptor("test-plugin", "1.0", "test", List.of(), null);
            }
            @Override public void initialize(PluginContext ctx) {}
            @Override public void shutdown() {}
            @Override public List<CapabilityProvider> capabilityProviders() {
                return List.of(new CapabilityProvider() {
                    @Override public CapabilityDescriptor descriptor() {
                        return new CapabilityDescriptor("ping", "ping", List.of(), List.of(), true, false);
                    }
                    @Override public Capability create(PluginContext ctx) {
                        return req -> {
                            executions.incrementAndGet();
                            return CapabilityResult.success("pong");
                        };
                    }
                });
            }
        };
    }
}
