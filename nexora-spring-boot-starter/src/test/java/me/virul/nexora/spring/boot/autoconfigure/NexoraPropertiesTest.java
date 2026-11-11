package me.virul.nexora.spring.boot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import me.virul.nexora.persistence.jdbc.PoolConfig;
import org.junit.jupiter.api.Test;

class NexoraPropertiesTest {

    @Test
    void poolOfOneConnectionDefaultsMinimumIdleToOne() {
        PoolConfig config = new NexoraProperties.Pool(1, null, null, null, null).toPoolConfig();

        assertThat(config.maximumPoolSize()).isEqualTo(1);
        assertThat(config.minimumIdle()).isEqualTo(1);
    }

    @Test
    void unsetPoolSettingsTakePoolConfigDefaults() {
        assertThat(new NexoraProperties.Pool(null, null, null, null, null).toPoolConfig())
                .isEqualTo(PoolConfig.defaults());
    }

    @Test
    void explicitPoolSettingsAreConverted() {
        PoolConfig config = new NexoraProperties.Pool(
                        4, 0, Duration.ofSeconds(2), Duration.ZERO, Duration.ofMillis(500))
                .toPoolConfig();

        assertThat(config).isEqualTo(new PoolConfig(4, 0, 2_000, 0, 500));
    }
}
