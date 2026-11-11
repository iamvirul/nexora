package me.virul.nexora.spring.boot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import me.virul.nexora.api.NexoraEngine.ReadinessReport;
import me.virul.nexora.persistence.ExecutionStore;
import me.virul.nexora.persistence.jdbc.HikariCPDataSourceFactory;
import me.virul.nexora.persistence.jdbc.JdbcExecutionStore;
import me.virul.nexora.persistence.jdbc.PoolConfig;
import me.virul.nexora.spi.NexoraPlugin;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class NexoraHealthAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(
                    AutoConfigurations.of(NexoraAutoConfiguration.class, NexoraHealthAutoConfiguration.class));

    @Test
    void reportsUpWithCheckDetailsWhenEngineIsReady() {
        runner.withUserConfiguration(PluginConfig.class)
                .withPropertyValues("nexora.tenant-id=acme")
                .run(context -> {
                    Health health = context.getBean("nexoraHealthIndicator", HealthIndicator.class)
                            .health();

                    assertThat(health.getStatus()).isEqualTo(Status.UP);
                    assertThat(health.getDetails())
                            .containsEntry(ReadinessReport.CHECK_PERSISTENCE, "UP")
                            .containsEntry(ReadinessReport.CHECK_MIGRATIONS, "UP")
                            .containsEntry(ReadinessReport.CHECK_PLUGINS, "UP")
                            .containsEntry(ReadinessReport.CHECK_EXECUTOR, "UP")
                            .containsEntry(
                                    NexoraHealthIndicator.DETAIL_ACTIVE_PLUGINS, List.of(GreetingPlugin.PLUGIN_ID))
                            .containsEntry(NexoraHealthIndicator.DETAIL_TENANT_ID, "acme");
                });
    }

    @Test
    void reportsDownWhenPersistenceIsUnreachable() {
        runner.withPropertyValues("nexora.persistence.jdbc-url=jdbc:h2:mem:health_" + System.nanoTime())
                .run(context -> {
                    // Closing the store's own pool leaves every later connection attempt failing.
                    context.getBean(ExecutionStore.class).close();

                    Health health = context.getBean(NexoraHealthIndicator.class).health();

                    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
                    assertThat(health.getDetails()).containsEntry(ReadinessReport.CHECK_PERSISTENCE, "DOWN");
                });
    }

    @Test
    void reportsDownWithinProbeTimeoutWhenPoolIsSaturated() {
        runner.withUserConfiguration(SaturablePoolConfig.class).run(context -> {
            HikariDataSource pool = context.getBean(HikariDataSource.class);
            List<Connection> held = new ArrayList<>();
            try {
                for (int i = 0; i < SaturablePoolConfig.POOL_SIZE; i++) {
                    held.add(pool.getConnection());
                }
                long started = System.nanoTime();

                Health health = context.getBean(NexoraHealthIndicator.class).health();

                long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                assertThat(health.getStatus()).isEqualTo(Status.DOWN);
                assertThat(health.getDetails()).containsEntry(ReadinessReport.CHECK_PERSISTENCE, "DOWN");
                // The pool would hold a borrower for 10s; the probe is bounded at 200ms.
                assertThat(elapsedMs).isLessThan(2_000);
            } finally {
                for (Connection connection : held) {
                    connection.close();
                }
            }
        });
    }

    @Test
    void isNotRegisteredWhenDisabledByProperty() {
        runner.withPropertyValues("management.health.nexora.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(NexoraHealthIndicator.class));
    }

    @Test
    void isNotRegisteredWithoutEngine() {
        runner.withPropertyValues("nexora.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(NexoraHealthIndicator.class));
    }

    @Test
    void isNotRegisteredWithoutSpringBootHealth() {
        runner.withClassLoader(new FilteredClassLoader(HealthIndicator.class)).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(NexoraHealthIndicator.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class SaturablePoolConfig {
        static final int POOL_SIZE = 2;

        @Bean(destroyMethod = "close")
        HikariDataSource nexoraPool() {
            return HikariCPDataSourceFactory.create(
                    "jdbc:h2:mem:saturable_" + System.nanoTime(), new PoolConfig(POOL_SIZE, POOL_SIZE, 10_000, 0));
        }

        @Bean
        ExecutionStore executionStore(HikariDataSource nexoraPool) {
            return new JdbcExecutionStore(nexoraPool, Duration.ofMillis(200));
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class PluginConfig {
        @Bean(destroyMethod = "")
        NexoraPlugin greetingPlugin() {
            return new GreetingPlugin();
        }
    }
}
