package com.nexora.tracing;

import com.nexora.core.context.TraceContext;

/**
 * Abstraction over a distributed tracing backend.
 * The default implementation is a no-op; swap in an OpenTelemetry-backed
 * implementation without touching any call sites.
 */
public interface Tracer {
    @SuppressWarnings("unused")
    Span startSpan(String operationName, TraceContext parent);
}
