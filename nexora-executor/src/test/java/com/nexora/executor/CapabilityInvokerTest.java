package com.nexora.executor;

import com.nexora.core.capability.CapabilityContract;
import com.nexora.core.capability.CapabilityRequest;
import com.nexora.core.capability.CapabilityResult;
import com.nexora.core.context.TraceContext;
import com.nexora.registry.DefaultCapabilityRegistry;
import com.nexora.spi.CapabilityDescriptor;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapabilityInvokerTest {

    @Test
    void doesNotRouteToFallbackBeforeMinimumSamples() {
        DefaultCapabilityRegistry registry = new DefaultCapabilityRegistry();
        CapabilityContractMonitor monitor = new CapabilityContractMonitor();

        AtomicInteger primaryCalls = new AtomicInteger();
        AtomicInteger fallbackCalls = new AtomicInteger();

        registry.register(primaryDescriptor(), request -> {
            primaryCalls.incrementAndGet();
            return CapabilityResult.failure("PRIMARY_FAILURE", "primary failed");
        });
        registry.register(descriptor("fallback"), request -> {
            fallbackCalls.incrementAndGet();
            return CapabilityResult.success("fallback-ok");
        });

        CapabilityInvoker invoker = new CapabilityInvoker(registry, monitor);

        for (int i = 0; i < 5; i++) {
            CapabilityResult result = invoker.invoke(request("primary"));
            assertFalse(result.succeeded());
        }

        assertEquals(5, primaryCalls.get());
        assertEquals(0, fallbackCalls.get());
    }

    @Test
    void routesToFallbackAfterContractBreach() {
        DefaultCapabilityRegistry registry = new DefaultCapabilityRegistry();
        CapabilityContractMonitor monitor = new CapabilityContractMonitor();

        AtomicInteger primaryCalls = new AtomicInteger();
        AtomicInteger fallbackCalls = new AtomicInteger();

        registry.register(primaryDescriptor(), request -> {
            primaryCalls.incrementAndGet();
            return CapabilityResult.failure("PRIMARY_FAILURE", "primary failed");
        });
        registry.register(descriptor("fallback"), request -> {
            fallbackCalls.incrementAndGet();
            return CapabilityResult.success("fallback-ok");
        });

        CapabilityInvoker invoker = new CapabilityInvoker(registry, monitor);

        for (int i = 0; i < 5; i++) {
            invoker.invoke(request("primary"));
        }

        CapabilityResult sixth = invoker.invoke(request("primary"));

        assertTrue(sixth.succeeded());
        assertEquals("fallback-ok", sixth.output());
        assertEquals(5, primaryCalls.get());
        assertEquals(1, fallbackCalls.get());
    }

    private static CapabilityRequest request(String capabilityId) {
        return new CapabilityRequest(
                capabilityId,
                "step-1",
                java.util.UUID.randomUUID().toString(),
                Map.of(),
                TraceContext.root(),
                Duration.ofSeconds(1)
        );
    }

    private static CapabilityDescriptor primaryDescriptor() {
        CapabilityContract contract = CapabilityContract.builder()
                .maxErrorRate(0.0)
                .fallback("fallback")
                .windowSize(20)
                .build();
        return new CapabilityDescriptor(
                "primary",
                "primary",
                List.of(),
                List.of(),
                true,
                false,
                contract
        );
    }

    private static CapabilityDescriptor descriptor(String id) {
        return new CapabilityDescriptor(id, id, List.of(), List.of(), true, false);
    }
}
