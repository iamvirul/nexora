package com.nexora.api;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NexoraEngineTest {

    @Test
    void builderRejectsInvalidDefaultPlanDeadline() {
        NexoraEngine.Builder builder = NexoraEngine.builder();

        assertThatThrownBy(() -> builder.withDefaultPlanDeadline(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("defaultPlanDeadline must be a positive duration");

        assertThatThrownBy(() -> builder.withDefaultPlanDeadline(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("defaultPlanDeadline must be a positive duration");

        assertThatThrownBy(() -> builder.withDefaultPlanDeadline(Duration.ofSeconds(-5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("defaultPlanDeadline must be a positive duration");
    }
}
