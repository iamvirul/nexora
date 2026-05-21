package com.nexora.tracing;

import com.nexora.core.context.TraceContext;

public interface Span {
    TraceContext context();
    @SuppressWarnings("unused")
    void setAttribute(String key, String value);
    @SuppressWarnings("unused")
    void recordException(Throwable t);
    void setStatus(SpanStatus status);
    SpanStatus status();
    void end();
}
