package me.virul.nexora.core.context;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Identifies a span of a distributed trace, propagated from the execution to every capability call.
 *
 * @param traceId      the id shared by every span of the trace
 * @param spanId       the id of this span
 * @param parentSpanId the id of the parent span, or {@code null} for a root span
 * @param baggage      key-value pairs carried across the trace; never null and immutable
 * @param sampled      whether the trace is recorded and exported
 */
public record TraceContext(
        String traceId, String spanId, String parentSpanId, Map<String, String> baggage, boolean sampled) {

    /**
     * Validates the ids and copies the baggage, treating {@code null} as empty.
     *
     * @throws NullPointerException if {@code traceId} or {@code spanId} is null
     */
    public TraceContext {
        Objects.requireNonNull(traceId, "traceId must not be null");
        Objects.requireNonNull(spanId, "spanId must not be null");
        baggage = baggage == null ? Map.of() : Map.copyOf(baggage);
    }

    /**
     * Creates a sampled context, preserving the behavior of the original four-argument API.
     *
     * @param traceId      the id shared by every span of the trace
     * @param spanId       the id of this span
     * @param parentSpanId the id of the parent span, or {@code null} for a root span
     * @param baggage      key-value pairs carried across the trace, or {@code null} for none
     */
    public TraceContext(String traceId, String spanId, String parentSpanId, Map<String, String> baggage) {
        this(traceId, spanId, parentSpanId, baggage, true);
    }

    /**
     * Starts a new sampled trace.
     *
     * @return a root span with random 16-character hex trace and span ids and no baggage
     */
    public static TraceContext root() {
        return new TraceContext(newId(), newId(), null, Map.of(), true);
    }

    /**
     * Creates a child span that shares this trace but gets its own span ID.
     *
     * @return a span whose parent is this one, with the same trace id, baggage and sampling decision
     */
    public TraceContext childSpan() {
        return new TraceContext(traceId, newId(), spanId, baggage, sampled);
    }

    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
