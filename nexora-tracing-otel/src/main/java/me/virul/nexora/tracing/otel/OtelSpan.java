package me.virul.nexora.tracing.otel;

import io.opentelemetry.api.trace.StatusCode;
import me.virul.nexora.core.context.TraceContext;
import me.virul.nexora.tracing.Span;
import me.virul.nexora.tracing.SpanStatus;

final class OtelSpan implements Span {

    private final TraceContext context;
    private final io.opentelemetry.api.trace.Span delegate;
    private final Runnable onEnd;
    private volatile SpanStatus status = SpanStatus.UNSET;

    OtelSpan(TraceContext context, io.opentelemetry.api.trace.Span delegate, Runnable onEnd) {
        this.context = context;
        this.delegate = delegate;
        this.onEnd = onEnd;
    }

    @Override
    public TraceContext context() {
        return context;
    }

    @Override
    public void setAttribute(String key, String value) {
        delegate.setAttribute(key, value);
    }

    @Override
    public void recordException(Throwable t) {
        delegate.recordException(t);
    }

    @Override
    public void setStatus(SpanStatus status) {
        this.status = status;
        delegate.setStatus(toStatusCode(status));
    }

    @Override
    public SpanStatus status() {
        return status;
    }

    @Override
    public void end() {
        delegate.end();
        onEnd.run();
    }

    private static StatusCode toStatusCode(SpanStatus status) {
        return switch (status) {
            case OK -> StatusCode.OK;
            case ERROR -> StatusCode.ERROR;
            case UNSET -> StatusCode.UNSET;
        };
    }
}
