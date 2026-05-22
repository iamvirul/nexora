---
id: nexora-tracing
title: nexora-tracing
sidebar_position: 9
---

# nexora-tracing

Tracer and Span interfaces. The default implementation is a no-op; swap in an OpenTelemetry-backed implementation without touching any call sites.

## Interfaces

### Tracer

```java
public interface Tracer {
    Span startSpan(String operationName, TraceContext parent);
}
```

### Span

```java
public interface Span {
    void setAttribute(String key, String value);
    void setStatus(SpanStatus status);
    void end();
}
```

`SpanStatus` values: `OK`, `ERROR`.

---

## Default: NoopTracer

`NoopTracer.INSTANCE` is used when no tracer is configured. All calls are no-ops. Zero overhead.

---

## Using OpenTelemetry

Add the OpenTelemetry SDK and implement the two interfaces:

```java
public class OtelTracer implements com.nexora.tracing.Tracer {

    private final io.opentelemetry.api.trace.Tracer otelTracer;

    public OtelTracer(OpenTelemetry otel) {
        this.otelTracer = otel.getTracer("nexora");
    }

    @Override
    public Span startSpan(String operationName, TraceContext parent) {
        Context ctx = extractContext(parent);  // propagate W3C traceparent
        io.opentelemetry.api.trace.Span span = otelTracer
            .spanBuilder(operationName)
            .setParent(ctx)
            .startSpan();
        return new OtelSpan(span);
    }
}

public class OtelSpan implements com.nexora.tracing.Span {

    private final io.opentelemetry.api.trace.Span inner;

    @Override
    public void setAttribute(String key, String value) {
        inner.setAttribute(key, value);
    }

    @Override
    public void setStatus(SpanStatus status) {
        inner.setStatus(status == SpanStatus.OK
            ? StatusCode.OK : StatusCode.ERROR);
    }

    @Override
    public void end() { inner.end(); }
}
```

Register it on the engine:

```java
NexoraEngine engine = NexoraEngine.builder()
    .withTracer(new OtelTracer(GlobalOpenTelemetry.get()))
    .build();
```

---

## TraceContext

Carries `traceId` and `spanId` strings. Created at the start of each execution and propagated through every `ExecutionContext`. Accessible inside capabilities:

```java
public CapabilityResult execute(CapabilityRequest request) {
    String traceId = request.context().getTraceContext().traceId();
    MDC.put("trace_id", traceId);  // attach to log context
    ...
}
```
