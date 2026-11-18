package me.virul.nexora.runtime.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class DrainResultTest {

    @Test
    void cleanWhenNoExecutionIsLeftUnfinished() {
        DrainResult drain = new DrainResult(3, 2, 1, 0, Duration.ofMillis(40));

        assertThat(drain.clean()).isTrue();
    }

    @Test
    void notCleanWhenAnExecutionIsLeftUnfinished() {
        DrainResult drain = new DrainResult(2, 1, 1, 1, Duration.ofMillis(40));

        assertThat(drain.clean()).isFalse();
    }

    @Test
    void rejectsNegativeCounts() {
        assertThatThrownBy(() -> new DrainResult(-1, 0, 0, 0, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DrainResult(0, 0, 0, -1, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullElapsed() {
        assertThatThrownBy(() -> new DrainResult(0, 0, 0, 0, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void shuttingDownExceptionIsAnIllegalStateWithAClearMessage() {
        assertThat(new EngineShuttingDownException())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("shutting down");
    }
}
