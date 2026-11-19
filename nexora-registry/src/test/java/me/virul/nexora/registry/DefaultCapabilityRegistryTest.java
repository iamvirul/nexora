package me.virul.nexora.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import me.virul.nexora.core.capability.CapabilityResult;
import me.virul.nexora.spi.Capability;
import me.virul.nexora.spi.CapabilityDescriptor;
import org.junit.jupiter.api.Test;

class DefaultCapabilityRegistryTest {

    private static final Capability OK = request -> CapabilityResult.success("ok");

    private final DefaultCapabilityRegistry registry = new DefaultCapabilityRegistry();

    @Test
    void findsARegisteredCapabilityAndItsDescriptor() {
        CapabilityDescriptor descriptor = descriptor("cap");

        registry.register(descriptor, OK);

        assertThat(registry.find("cap")).containsSame(OK);
        assertThat(registry.findDescriptor("cap")).containsSame(descriptor);
        assertThat(registry.listAll()).containsExactly(descriptor);
    }

    @Test
    void findsNothingForAnUnknownCapability() {
        assertThat(registry.find("missing")).isEmpty();
        assertThat(registry.findDescriptor("missing")).isEmpty();
        assertThat(registry.listAll()).isEmpty();
    }

    @Test
    void registeringAnExistingIdReplacesIt() {
        Capability replacement = request -> CapabilityResult.success("new");
        CapabilityDescriptor newDescriptor = descriptor("cap");
        registry.register(descriptor("cap"), OK);

        registry.register(newDescriptor, replacement);

        assertThat(registry.find("cap")).containsSame(replacement);
        assertThat(registry.findDescriptor("cap")).containsSame(newDescriptor);
        assertThat(registry.listAll()).hasSize(1);
    }

    @Test
    void deregisterRemovesTheCapabilityAndIgnoresUnknownIds() {
        registry.register(descriptor("cap"), OK);

        registry.deregister("cap");
        registry.deregister("missing");

        assertThat(registry.find("cap")).isEmpty();
        assertThat(registry.listAll()).isEmpty();
    }

    @Test
    void rejectsNullArguments() {
        assertThatThrownBy(() -> registry.register(null, OK)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> registry.register(descriptor("cap"), null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> registry.deregister(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void readsDuringConcurrentRegistrationAlwaysSeeACompleteEntry() throws Exception {
        int writers = 4;
        int perWriter = 2_000;
        ExecutorService pool = Executors.newFixedThreadPool(writers * 2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Boolean>> tasks = new ArrayList<>();
            for (int w = 0; w < writers; w++) {
                String prefix = "w" + w + "-";
                tasks.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < perWriter; i++) {
                        registry.register(descriptor(prefix + i), OK);
                    }
                    return true;
                }));
                tasks.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < perWriter; i++) {
                        String id = prefix + i;
                        boolean hasCapability = registry.find(id).isPresent();
                        boolean hasDescriptor = registry.findDescriptor(id).isPresent();
                        if (hasCapability && !hasDescriptor) return false;
                    }
                    return registry.listAll().stream()
                            .allMatch(d -> registry.find(d.id()).isPresent());
                }));
            }
            start.countDown();
            for (Future<Boolean> task : tasks) {
                assertThat(task.get(30, TimeUnit.SECONDS)).isTrue();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(registry.listAll()).hasSize(writers * perWriter);
    }

    private static CapabilityDescriptor descriptor(String id) {
        return new CapabilityDescriptor(id, id, List.of(), List.of(), true, false);
    }
}
