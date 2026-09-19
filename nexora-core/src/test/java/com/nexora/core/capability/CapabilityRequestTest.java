package com.nexora.core.capability;

import com.nexora.core.context.TraceContext;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CapabilityRequestTest {

    @Test
    void shouldRequireExecutionId() {
        assertThatThrownBy(() -> new CapabilityRequest(
                "cap", "step-1", "idem-1", Map.of(), TraceContext.root(), null, null, 0))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("executionId");
    }

    @Test
    void shouldStripNullInputValues() {
        Map<String, Object> inputs = new java.util.HashMap<>();
        inputs.put("a", "1");
        inputs.put("b", null);

        CapabilityRequest request = new CapabilityRequest(
                "cap", "step-1", "idem-1", inputs,
                TraceContext.root(), null, "exec-1", 0);

        assertThat(request.inputs()).containsExactly(Map.entry("a", "1"));
    }

    @Test
    void withAttemptReturnsCopyWithNewAttemptNumberOnly() {
        CapabilityRequest request = new CapabilityRequest(
                "cap", "step-1", "idem-1", Map.of(), TraceContext.root(),
                Duration.ofSeconds(1), "exec-1", 0);

        CapabilityRequest retried = request.withAttempt(2);

        assertThat(retried.attemptNumber()).isEqualTo(2);
        assertThat(retried.capabilityId()).isEqualTo(request.capabilityId());
        assertThat(retried.stepId()).isEqualTo(request.stepId());
        assertThat(retried.idempotencyKey()).isEqualTo(request.idempotencyKey());
        assertThat(retried.inputs()).isEqualTo(request.inputs());
        assertThat(retried.traceContext()).isEqualTo(request.traceContext());
        assertThat(retried.timeout()).isEqualTo(request.timeout());
        assertThat(retried.executionId()).isEqualTo(request.executionId());
        assertThat(request.attemptNumber()).isEqualTo(0);
    }
}
