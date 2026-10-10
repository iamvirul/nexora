package com.nexora.executor.interceptor;

import com.nexora.core.capability.CapabilityRequest;
import com.nexora.core.capability.CapabilityResult;
import com.nexora.core.context.TraceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TimeoutInterceptorTest {

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void returnsCapabilityResultWithinTimeout() {
        TimeoutInterceptor interceptor = new TimeoutInterceptor(executor, Duration.ofSeconds(5));

        CapabilityResult result = interceptor.intercept(request(), req -> CapabilityResult.success("ok"));

        assertThat(result.succeeded()).isTrue();
    }

    @Test
    void timeoutInterruptsTheCapabilityInsteadOfLeavingItRunning() throws Exception {
        TimeoutInterceptor interceptor = new TimeoutInterceptor(executor, Duration.ofMillis(100));
        CountDownLatch capabilityInterrupted = new CountDownLatch(1);

        CapabilityResult result = interceptor.intercept(request(), blockingCapability(new CountDownLatch(1), capabilityInterrupted));

        assertThat(result.failureCode()).isEqualTo("EXECUTION_TIMEOUT");
        assertThat(capabilityInterrupted.await(5, TimeUnit.SECONDS))
                .as("timed-out capability should be interrupted").isTrue();
    }

    @Test
    void interruptingTheCallerInterruptsTheCapability() throws Exception {
        TimeoutInterceptor interceptor = new TimeoutInterceptor(executor, Duration.ofSeconds(30));
        CountDownLatch capabilityStarted = new CountDownLatch(1);
        CountDownLatch capabilityInterrupted = new CountDownLatch(1);
        AtomicReference<CapabilityResult> callerResult = new AtomicReference<>();

        Thread caller = Thread.ofVirtual().start(() -> callerResult.set(
                interceptor.intercept(request(), blockingCapability(capabilityStarted, capabilityInterrupted))));
        assertThat(capabilityStarted.await(5, TimeUnit.SECONDS)).isTrue();

        caller.interrupt(); // what HaltSignal does to a step thread on cancel
        caller.join(Duration.ofSeconds(5));

        assertThat(callerResult.get().failureCode()).isEqualTo("INTERRUPTED");
        assertThat(capabilityInterrupted.await(5, TimeUnit.SECONDS))
                .as("cancel should reach the capability thread").isTrue();
    }

    /** Capability that blocks until interrupted, signalling when it starts and when it is interrupted. */
    private static com.nexora.executor.InterceptorChain blockingCapability(
            CountDownLatch started, CountDownLatch interrupted) {
        return req -> {
            started.countDown();
            try {
                new CountDownLatch(1).await();
                return CapabilityResult.success("unreachable");
            } catch (InterruptedException e) {
                interrupted.countDown();
                return CapabilityResult.failure("INTERRUPTED", "interrupted");
            }
        };
    }

    private static CapabilityRequest request() {
        return new CapabilityRequest(
                "cap", "step-1", "idem-1", Map.of(), TraceContext.root(), null, "exec-1", 0);
    }
}
