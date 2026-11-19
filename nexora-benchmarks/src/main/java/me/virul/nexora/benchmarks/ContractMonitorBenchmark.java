package me.virul.nexora.benchmarks;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import me.virul.nexora.core.capability.CapabilityContract;
import me.virul.nexora.core.capability.CapabilityRequest;
import me.virul.nexora.core.capability.CapabilityResult;
import me.virul.nexora.core.context.TraceContext;
import me.virul.nexora.executor.CapabilityContractMonitor;
import me.virul.nexora.executor.CapabilityInvoker;
import me.virul.nexora.registry.DefaultCapabilityRegistry;
import me.virul.nexora.spi.CapabilityDescriptor;
import me.virul.nexora.spi.CapabilityRegistry;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/**
 * The cost the capability contract monitor adds to each call. {@link #direct()} calls the capability
 * itself, the baseline; {@link #invoke()} goes through {@link CapabilityInvoker}, which records every
 * call in the monitor's sliding window and, for a contract with a fallback, checks the breaker before
 * the call. {@link #invokeContended()} does the same from 4 threads on one capability, whose window
 * they share.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class ContractMonitorBenchmark {

    static final String FALLBACK = "bench.fallback";

    /** The contract the capability is registered with. */
    public enum Contract {
        /** No thresholds: calls are recorded, the breaker is never checked. */
        NONE,
        /** p99 latency and error rate thresholds with a fallback: the breaker is checked on every call. */
        WITH_FALLBACK;

        CapabilityContract contract() {
            return switch (this) {
                case NONE -> CapabilityContract.none();
                case WITH_FALLBACK ->
                    CapabilityContract.builder()
                            .p99Latency(Duration.ofSeconds(1))
                            .maxErrorRate(0.5)
                            .fallback(FALLBACK)
                            .build();
            };
        }
    }

    /** The contract under test. */
    @Param
    public Contract contract;

    private CapabilityInvoker invoker;
    private CapabilityRequest request;

    /** Registers the capability and its fallback. */
    @Setup
    public void setUp() {
        CapabilityRegistry registry = new DefaultCapabilityRegistry();
        registry.register(descriptor(Capabilities.NOOP, contract.contract()), Capabilities.NOOP_CAPABILITY);
        registry.register(descriptor(FALLBACK, CapabilityContract.none()), Capabilities.NOOP_CAPABILITY);
        invoker = new CapabilityInvoker(registry, new CapabilityContractMonitor());
        request = new CapabilityRequest(
                Capabilities.NOOP, "step", "idempotency-key", Map.of(), TraceContext.root(), null, "execution", 0);
    }

    /**
     * Calls the capability directly.
     *
     * @return the result, consumed by JMH
     */
    @Benchmark
    public CapabilityResult direct() {
        return Capabilities.NOOP_CAPABILITY.execute(request);
    }

    /**
     * Calls the capability through the invoker and its contract monitor.
     *
     * @return the result, consumed by JMH
     */
    @Benchmark
    public CapabilityResult invoke() {
        return invoker.invoke(request);
    }

    /**
     * {@link #invoke()} from 4 threads at once.
     *
     * @return the result, consumed by JMH
     */
    @Benchmark
    @Threads(4)
    public CapabilityResult invokeContended() {
        return invoker.invoke(request);
    }

    private static CapabilityDescriptor descriptor(String id, CapabilityContract contract) {
        return new CapabilityDescriptor(id, "Benchmark capability", List.of(), List.of(), true, false, contract);
    }
}
