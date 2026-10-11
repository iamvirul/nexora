package com.nexora.tracing;

import com.nexora.core.context.TraceContext;

final class NoopSpan implements Span {

    private final TraceContext context;
    private SpanStatus status = SpanStatus.UNSET;

    NoopSpan(TraceContext context) {
        this.context = context;
    }

    @Override
    public TraceContext context() {
        return context;
    }

    @Override
    public void setAttribute(String key, String value) {}

    @Override
    public void recordException(Throwable t) {}

    @Override
    public void setStatus(SpanStatus status) {
        this.status = status;
    }

    @Override
    public SpanStatus status() {
        return status;
    }

    @Override
    public void end() {}
}
