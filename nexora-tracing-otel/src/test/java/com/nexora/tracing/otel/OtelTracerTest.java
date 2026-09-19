package com.nexora.tracing.otel;

import com.nexora.core.context.TraceContext;
import com.nexora.tracing.Span;
import com.nexora.tracing.SpanStatus;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.data.StatusData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OtelTracerTest {

    private InMemorySpanExporter exporter;
    private OtelTracer tracer;

    @BeforeEach
    void setUp() {
        exporter = InMemorySpanExporter.create();
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder().setTracerProvider(provider).build();
        tracer = OtelTracer.forSdk(sdk);
    }

    @AfterEach
    void tearDown() {
        tracer.close();
    }

    @Test
    void startSpanExportsSpanWithAttributesAndStatus() {
        Span span = tracer.startSpan("capability.send-email", null);
        span.setAttribute("execution_id", "exec-1");
        span.setAttribute("step_id", "step-1");
        span.setAttribute("capability_id", "send-email");
        span.setAttribute("attempt_number", "0");
        span.setStatus(SpanStatus.OK);
        span.end();

        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);

        SpanData data = spans.get(0);
        assertThat(data.getName()).isEqualTo("capability.send-email");
        assertThat(data.getStatus().getStatusCode()).isEqualTo(StatusData.ok().getStatusCode());
        assertThat(data.getAttributes().asMap())
                .containsEntry(io.opentelemetry.api.common.AttributeKey.stringKey("execution_id"), "exec-1")
                .containsEntry(io.opentelemetry.api.common.AttributeKey.stringKey("step_id"), "step-1")
                .containsEntry(io.opentelemetry.api.common.AttributeKey.stringKey("capability_id"), "send-email")
                .containsEntry(io.opentelemetry.api.common.AttributeKey.stringKey("attempt_number"), "0");
    }

    @Test
    void childSpanSharesTraceWithLiveParent() {
        Span parent = tracer.startSpan("plan.execute", null);
        Span child = tracer.startSpan("capability.send-email", parent.context());
        child.end();
        parent.end();

        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertThat(spans).hasSize(2);
        String traceId = spans.get(0).getTraceId();
        assertThat(spans).allSatisfy(s -> assertThat(s.getTraceId()).isEqualTo(traceId));

        SpanData childData = spans.stream().filter(s -> s.getName().equals("capability.send-email")).findFirst().orElseThrow();
        SpanData parentData = spans.stream().filter(s -> s.getName().equals("plan.execute")).findFirst().orElseThrow();
        assertThat(childData.getParentSpanId()).isEqualTo(parentData.getSpanId());
    }

    @Test
    void remoteParentFromPropagatedContextStillLinksTrace() {
        TraceContext remoteParent = new TraceContext("aaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbb", null, java.util.Map.of());

        Span span = tracer.startSpan("capability.send-email", remoteParent);
        span.end();

        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);
        assertThat(spans.get(0).getTraceId()).isEqualTo(W3CTraceparent.expandTraceId(remoteParent.traceId()));
        assertThat(spans.get(0).getParentSpanId()).isEqualTo(remoteParent.spanId());
    }

    @Test
    void remoteUnsampledParentPreservesSamplingDecision() {
        TraceContext remoteParent = new TraceContext(
                "aaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbb", null, java.util.Map.of(), false);

        Span span = tracer.startSpan("capability.send-email", remoteParent);
        assertThat(span.context().sampled()).isFalse();
        span.end();

        assertThat(exporter.getFinishedSpanItems()).isEmpty();
    }
}
