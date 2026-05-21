package com.nexora.core.capability;

import com.nexora.core.plan.PlanAmendment;
import com.nexora.core.plan.SkipStepAmendment;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CapabilityResultTest {

    @Test
    void shouldCreateSuccessResult() {
        CapabilityResult result = CapabilityResult.success(Map.of("status", "OK"));
        
        assertThat(result.succeeded()).isTrue();
        assertThat((Map<String, String>) result.output()).containsEntry("status", "OK");
        assertThat(result.amendments()).isEmpty();
    }

    @Test
    void shouldCreateSuccessResultWithAmendments() {
        PlanAmendment skip = new SkipStepAmendment("step-1");
        CapabilityResult result = CapabilityResult.success(Map.of(), java.util.List.of(skip));
        
        assertThat(result.succeeded()).isTrue();
        assertThat(result.amendments()).containsExactly(skip);
    }

    @Test
    void shouldCreateFailureResult() {
        CapabilityResult result = CapabilityResult.failure("ERROR_CODE", "Something failed");
        
        assertThat(result.succeeded()).isFalse();
        assertThat(result.failureCode()).isEqualTo("ERROR_CODE");
        assertThat(result.failureMessage()).isEqualTo("Something failed");
    }

    @Test
    void failureResultHasNullOutput() {
        CapabilityResult result = CapabilityResult.failure("ERR", "msg");
        
        assertThat(result.output()).isNull();
    }
}
