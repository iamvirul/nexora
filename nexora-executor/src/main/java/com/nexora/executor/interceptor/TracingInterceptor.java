package com.nexora.executor.interceptor;

import com.nexora.core.capability.CapabilityRequest;
import com.nexora.core.capability.CapabilityResult;
import com.nexora.executor.ExecutionInterceptor;
import com.nexora.executor.InterceptorChain;
import com.nexora.tracing.Span;
import com.nexora.tracing.SpanStatus;
import com.nexora.tracing.Tracer;

import java.util.Objects;

public final class TracingInterceptor implements ExecutionInterceptor {

    private final Tracer tracer;

    public TracingInterceptor(Tracer tracer) {
        this.tracer = Objects.requireNonNull(tracer);
    }

    @Override
    public CapabilityResult intercept(CapabilityRequest request, InterceptorChain chain) {
        Span span = tracer.startSpan("capability." + request.capabilityId(), request.traceContext());
        span.setAttribute("execution_id", request.executionId());
        span.setAttribute("step_id", request.stepId());
        span.setAttribute("capability_id", request.capabilityId());
        span.setAttribute("attempt_number", String.valueOf(request.attemptNumber()));

        try {
            CapabilityResult result = chain.proceed(request);
            span.setStatus(result.succeeded() ? SpanStatus.OK : SpanStatus.ERROR);
            if (!result.succeeded()) {
                span.setAttribute("error_code", result.failureCode());
            }
            return result;
        } catch (Exception e) {
            span.recordException(e);
            span.setStatus(SpanStatus.ERROR);
            throw e;
        } finally {
            span.end();
        }
    }
}
