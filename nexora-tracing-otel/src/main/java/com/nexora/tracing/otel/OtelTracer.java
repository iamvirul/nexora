package com.nexora.tracing.otel;

import com.nexora.core.context.TraceContext;
import com.nexora.tracing.Span;
import com.nexora.tracing.Tracer;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * {@link Tracer} backed by the OpenTelemetry Java SDK, exporting spans over OTLP.
 *
 * <p>Spans started in-process are linked as real parent/child via a live-span cache
 * keyed by our internal {@link TraceContext#spanId()}; a parent received only as a
 * propagated id (e.g. via an inbound {@code traceparent} header, with no live span
 * object in this process) is linked as a remote parent instead.
 */
public final class OtelTracer implements Tracer, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(OtelTracer.class);
    private static final String INSTRUMENTATION_NAME = "nexora";

    private final OpenTelemetrySdk sdk;
    private final io.opentelemetry.api.trace.Tracer delegate;
    private final ConcurrentHashMap<String, io.opentelemetry.api.trace.Span> liveSpans = new ConcurrentHashMap<>();

    private OtelTracer(OpenTelemetrySdk sdk) {
        this.sdk = sdk;
        this.delegate = sdk.getTracer(INSTRUMENTATION_NAME);
    }

    /** Builds an {@link OtelTracer} exporting spans via OTLP/HTTP to {@code otlpEndpoint}. */
    public static OtelTracer forEndpoint(String otlpEndpoint) {
        Objects.requireNonNull(otlpEndpoint, "otlpEndpoint must not be null");

        Resource resource = Resource.getDefault().merge(
                Resource.create(Attributes.of(AttributeKey.stringKey("service.name"), "nexora")));

        OtlpHttpSpanExporter exporter = OtlpHttpSpanExporter.builder()
                .setEndpoint(normalizeEndpoint(otlpEndpoint))
                .build();

        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .setResource(resource)
                .addSpanProcessor(BatchSpanProcessor.builder(exporter).build())
                .build();

        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .build();

        log.info("OtelTracer configured, exporting spans via OTLP/HTTP to {}", otlpEndpoint);
        return new OtelTracer(sdk);
    }

    /** Package-visible: lets tests inject a pre-built SDK (e.g. wired to an in-memory exporter). */
    static OtelTracer forSdk(OpenTelemetrySdk sdk) {
        return new OtelTracer(sdk);
    }

    @Override
    public Span startSpan(String operationName, TraceContext parent) {
        TraceContext childCtx = parent != null ? parent.childSpan() : TraceContext.root();

        var builder = delegate.spanBuilder(operationName).setSpanKind(SpanKind.INTERNAL);
        if (parent != null) {
            io.opentelemetry.api.trace.Span liveParent = liveSpans.get(parent.spanId());
            builder.setParent(liveParent != null
                    ? Context.root().with(liveParent)
                    : Context.root().with(io.opentelemetry.api.trace.Span.wrap(remoteParentContext(parent))));
        }

        io.opentelemetry.api.trace.Span otelSpan = builder.startSpan();
        liveSpans.put(childCtx.spanId(), otelSpan);
        return new OtelSpan(childCtx, otelSpan, () -> liveSpans.remove(childCtx.spanId()));
    }

    @Override
    public void close() {
        sdk.getSdkTracerProvider().shutdown().join(10, TimeUnit.SECONDS);
    }

    private static SpanContext remoteParentContext(TraceContext parent) {
        return SpanContext.createFromRemoteParent(
                W3CTraceparent.expandTraceId(parent.traceId()),
                parent.spanId(),
                parent.sampled() ? TraceFlags.getSampled() : TraceFlags.getDefault(),
                TraceState.getDefault());
    }

    private static String normalizeEndpoint(String endpoint) {
        String trimmed = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        return trimmed.endsWith("/v1/traces") ? trimmed : trimmed + "/v1/traces";
    }
}
