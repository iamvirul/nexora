package me.virul.nexora.tracing;

import me.virul.nexora.core.context.TraceContext;

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
