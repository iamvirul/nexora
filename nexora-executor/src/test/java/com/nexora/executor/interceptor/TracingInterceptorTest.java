package com.nexora.executor.interceptor;

import com.nexora.core.capability.CapabilityRequest;
import com.nexora.core.capability.CapabilityResult;
import com.nexora.core.context.TraceContext;
import com.nexora.tracing.Span;
import com.nexora.tracing.SpanStatus;
import com.nexora.tracing.Tracer;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TracingInterceptorTest {

    @Test
    void attachesAllFourRequiredAttributesOnSuccess() {
        RecordingTracer tracer = new RecordingTracer();
        TracingInterceptor interceptor = new TracingInterceptor(tracer);
        CapabilityRequest request = request("exec-1", "step-1", "send-email", 2);

        CapabilityResult result = interceptor.intercept(request, req -> CapabilityResult.success("ok"));

        assertThat(result.succeeded()).isTrue();
        assertThat(tracer.lastSpan.attributes)
                .containsEntry("execution_id", "exec-1")
                .containsEntry("step_id", "step-1")
                .containsEntry("capability_id", "send-email")
                .containsEntry("attempt_number", "2");
        assertThat(tracer.lastSpan.status).isEqualTo(SpanStatus.OK);
        assertThat(tracer.lastSpan.ended).isTrue();
    }

    @Test
    void setsErrorStatusAndCodeOnFailure() {
        RecordingTracer tracer = new RecordingTracer();
        TracingInterceptor interceptor = new TracingInterceptor(tracer);
        CapabilityRequest request = request("exec-1", "step-1", "send-email", 0);

        CapabilityResult result = interceptor.intercept(request, req -> CapabilityResult.failure("BAD_INPUT", "nope"));

        assertThat(result.succeeded()).isFalse();
        assertThat(tracer.lastSpan.status).isEqualTo(SpanStatus.ERROR);
        assertThat(tracer.lastSpan.attributes).containsEntry("error_code", "BAD_INPUT");
        assertThat(tracer.lastSpan.ended).isTrue();
    }

    @Test
    void endsSpanAndRecordsExceptionEvenWhenChainThrows() {
        RecordingTracer tracer = new RecordingTracer();
        TracingInterceptor interceptor = new TracingInterceptor(tracer);
        CapabilityRequest request = request("exec-1", "step-1", "send-email", 0);
        RuntimeException boom = new RuntimeException("boom");

        assertThatThrownBy(() -> interceptor.intercept(request, req -> { throw boom; }))
                .isSameAs(boom);

        assertThat(tracer.lastSpan.status).isEqualTo(SpanStatus.ERROR);
        assertThat(tracer.lastSpan.recordedExceptions).containsExactly(boom);
        assertThat(tracer.lastSpan.ended).isTrue();
    }

    private static CapabilityRequest request(String executionId, String stepId, String capabilityId, int attempt) {
        return new CapabilityRequest(
                capabilityId, stepId, "idem-1", Map.of(), TraceContext.root(),
                Duration.ofSeconds(1), executionId, attempt);
    }

    private static final class RecordingTracer implements Tracer {
        RecordingSpan lastSpan;

        @Override
        public Span startSpan(String operationName, TraceContext parent) {
            lastSpan = new RecordingSpan(parent != null ? parent.childSpan() : TraceContext.root());
            return lastSpan;
        }
    }

    private static final class RecordingSpan implements Span {
        private final TraceContext context;
        final Map<String, String> attributes = new HashMap<>();
        final java.util.List<Throwable> recordedExceptions = new java.util.ArrayList<>();
        SpanStatus status = SpanStatus.UNSET;
        boolean ended = false;

        RecordingSpan(TraceContext context) {
            this.context = context;
        }

        @Override public TraceContext context() { return context; }
        @Override public void setAttribute(String key, String value) { attributes.put(key, value); }
        @Override public void recordException(Throwable t) { recordedExceptions.add(t); }
        @Override public void setStatus(SpanStatus status) { this.status = status; }
        @Override public SpanStatus status() { return status; }
        @Override public void end() { ended = true; }
    }
}
