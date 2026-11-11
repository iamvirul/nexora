package me.virul.nexora.spring.boot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import me.virul.nexora.api.NexoraEngine.ReadinessReport;
import me.virul.nexora.persistence.ExecutionStore;
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
    static class PluginConfig {
        @Bean(destroyMethod = "")
        NexoraPlugin greetingPlugin() {
            return new GreetingPlugin();
        }
    }
}
