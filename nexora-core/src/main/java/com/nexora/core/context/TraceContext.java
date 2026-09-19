package com.nexora.core.context;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record TraceContext(
        String traceId,
        String spanId,
        String parentSpanId,   // null for root spans
        Map<String, String> baggage,
        boolean sampled
) {
    public TraceContext {
        Objects.requireNonNull(traceId, "traceId must not be null");
        Objects.requireNonNull(spanId, "spanId must not be null");
        baggage = baggage == null ? Map.of() : Map.copyOf(baggage);
    }

    /** Creates a sampled context, preserving the behavior of the original four-argument API. */
    public TraceContext(String traceId, String spanId, String parentSpanId, Map<String, String> baggage) {
        this(traceId, spanId, parentSpanId, baggage, true);
    }

    public static TraceContext root() {
        return new TraceContext(newId(), newId(), null, Map.of(), true);
    }

    /** Creates a child span that shares this trace but gets its own span ID. */
    public TraceContext childSpan() {
        return new TraceContext(traceId, newId(), spanId, baggage, sampled);
    }

    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
