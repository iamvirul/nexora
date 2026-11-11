package me.virul.nexora.spring.boot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import me.virul.nexora.api.NexoraEngine;
import me.virul.nexora.core.execution.ExecutionResult;
import me.virul.nexora.core.execution.ExecutionStatus;
import me.virul.nexora.core.plan.InputBinding;
import me.virul.nexora.persistence.ExecutionStore;
import me.virul.nexora.persistence.jdbc.JdbcExecutionStore;
import me.virul.nexora.planner.model.StepDefinition;
import me.virul.nexora.retry.ExponentialBackoffPolicy;
import me.virul.nexora.retry.RetryPolicy;
import me.virul.nexora.spi.NexoraPlugin;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

class NexoraAutoConfigurationTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(NexoraAutoConfiguration.class));

    @Test
    void createsEngineWithBuilderDefaultsWhenNothingIsConfigured() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(NexoraEngine.class);
            NexoraEngine engine = context.getBean(NexoraEngine.class);
            assertThat(engine.getStore()).isNull();
            assertThat(engine.tenantScope()).isEmpty();
            assertThat(engine.activePluginIds()).isEmpty();
            assertThat(context).doesNotHaveBean(RetryPolicy.class);
        });
    }

    @Test
    void createsNoEngineWhenDisabled() {
        runner.withPropertyValues("nexora.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(NexoraEngine.class));
    }

    @Test
    void backsOffWhenApplicationDefinesItsOwnEngine() {
        runner.withUserConfiguration(OwnEngineConfig.class).run(context -> {
            assertThat(context).hasSingleBean(NexoraEngine.class);
            assertThat(context.getBean(NexoraEngine.class)).isSameAs(OwnEngineConfig.ENGINE);
        });
    }

    @Test
    void createsNoStoreOrRetryPolicyWhenApplicationDefinesItsOwnEngine() {
        // An unreachable URL: creating the store would fail startup of an app that never uses it.
        runner.withUserConfiguration(OwnEngineConfig.class)
                .withPropertyValues(
                        "nexora.persistence.jdbc-url=jdbc:postgresql://127.0.0.1:1/unreachable",
                        "nexora.retry.max-attempts=3")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(ExecutionStore.class);
                    assertThat(context).doesNotHaveBean(RetryPolicy.class);
                });
    }

    @Test
    void failsStartupWhenDefaultTimeoutIsZero() {
        runner.withPropertyValues("nexora.default-timeout=0ms")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("defaultTimeout"));
    }

    @Test
    void executesIntentWithPluginAndStepDefinitionBeans() {
        runner.withUserConfiguration(GreetingConfig.class).run(context -> {
            NexoraEngine engine = context.getBean(NexoraEngine.class);
            assertThat(engine.activePluginIds()).containsExactly(GreetingPlugin.PLUGIN_ID);

            ExecutionResult result =
                    engine.execute("greet the user", Map.of("name", "ada")).get(10, TimeUnit.SECONDS);

            assertThat(result.status()).isEqualTo(ExecutionStatus.COMPLETED);
            assertThat(result.stepResults()).hasSize(1);
            assertThat(result.stepResults().get(0).capabilityResult().output())
                    .isEqualTo(Map.of("greeting", "hello ada"));
        });
    }

    @Test
    void bindsEngineToConfiguredTenant() {
        runner.withPropertyValues("nexora.tenant-id=acme")
                .run(context -> assertThat(context.getBean(NexoraEngine.class).tenantScope())
                        .contains("acme"));
    }

    @Test
    void failsStartupWhenTenantIdIsInvalid() {
        runner.withPropertyValues("nexora.tenant-id=not a tenant!")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .isInstanceOf(IllegalArgumentException.class));
    }

    @Test
    void failsStartupWhenStepOutputMaxBytesIsBelowMinimum() {
        runner.withPropertyValues("nexora.step-output-max-bytes=10")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .isInstanceOf(IllegalArgumentException.class));
    }

    @Test
    void createsJdbcStoreFromJdbcUrl() {
        runner.withPropertyValues(
                        "nexora.persistence.jdbc-url=jdbc:h2:mem:starter_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1",
                        "nexora.persistence.pool.maximum-pool-size=3",
                        "nexora.persistence.pool.minimum-idle=1")
                .run(context -> {
                    assertThat(context).hasSingleBean(ExecutionStore.class);
                    NexoraEngine engine = context.getBean(NexoraEngine.class);
                    assertThat(engine.getStore()).isSameAs(context.getBean(ExecutionStore.class));
                    assertThat(engine.readiness().ready()).isTrue();
                });
    }

    @Test
    void failsStartupWhenPoolSettingsAreInvalid() {
        runner.withPropertyValues(
                        "nexora.persistence.jdbc-url=jdbc:h2:mem:starter_" + System.nanoTime(),
                        "nexora.persistence.pool.maximum-pool-size=0")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("maximumPoolSize"));
    }

    @Test
    void prefersApplicationStoreOverJdbcUrl() {
        runner.withUserConfiguration(OwnStoreConfig.class)
                .withPropertyValues("nexora.persistence.jdbc-url=jdbc:h2:mem:unused_" + System.nanoTime())
                .run(context -> {
                    assertThat(context).hasSingleBean(ExecutionStore.class);
                    assertThat(context.getBean(NexoraEngine.class).getStore()).isSameAs(context.getBean("ownStore"));
                });
    }

    @Test
    void createsRetryPolicyFromProperties() {
        runner.withPropertyValues(
                        "nexora.retry.max-attempts=4",
                        "nexora.retry.initial-delay=50ms",
                        "nexora.retry.multiplier=3",
                        "nexora.retry.max-delay=2s")
                .run(context -> assertThat(context.getBean(RetryPolicy.class))
                        .isEqualTo(new ExponentialBackoffPolicy(
                                4, Duration.ofMillis(50), 3.0, Duration.ofSeconds(2), null)));
    }

    @Test
    void failsStartupWhenRetryMultiplierIsInvalid() {
        runner.withPropertyValues("nexora.retry.max-attempts=3", "nexora.retry.multiplier=1")
                .run(context ->
                        assertThat(context).hasFailed().getFailure().rootCause().hasMessageContaining("multiplier"));
    }

    @Test
    void appliesCustomizersInOrderAfterProperties() {
        runner.withUserConfiguration(CustomizerConfig.class)
                .withPropertyValues("nexora.tenant-id=from-properties")
                .run(context -> {
                    assertThat(context.getBean(CustomizerConfig.class).calls).containsExactly("first", "second");
                    assertThat(context.getBean(NexoraEngine.class).tenantScope())
                            .contains("from-customizer");
                });
    }

    @Test
    void propertiesToStringRedactsSecrets() {
        runner.withPropertyValues(
                        "nexora.webhook-secret=s3cret",
                        "nexora.persistence.jdbc-url=jdbc:h2:mem:secret_" + System.nanoTime() + ";PASSWORD=hunter2")
                .run(context -> assertThat(
                                context.getBean(NexoraProperties.class).toString())
                        .doesNotContain("s3cret")
                        .doesNotContain("hunter2"));
    }

    @Configuration(proxyBeanMethods = false)
    static class OwnEngineConfig {
        static final NexoraEngine ENGINE = NexoraEngine.builder().build();

        @Bean
        NexoraEngine ownEngine() {
            return ENGINE;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class GreetingConfig {
        // Plugin lifecycle belongs to the engine: no inferred destroy method calling shutdown() twice.
        @Bean(destroyMethod = "")
        NexoraPlugin greetingPlugin() {
            return new GreetingPlugin();
        }

        @Bean
        StepDefinition greetStep() {
            return StepDefinition.builder("greet", GreetingPlugin.CAPABILITY_ID)
                    .withMatcher(goal -> goal.contains("greet"))
                    .withInput("name", InputBinding.fromContext("intent.context.name"))
                    .build();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class OwnStoreConfig {
        @Bean
        ExecutionStore ownStore() {
            return JdbcExecutionStore.h2InMemory();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomizerConfig {
        final List<String> calls = new ArrayList<>();

        @Bean
        @Order(2)
        NexoraEngineBuilderCustomizer second() {
            return builder -> calls.add("second");
        }

        @Bean
        @Order(1)
        NexoraEngineBuilderCustomizer first() {
            return builder -> {
                calls.add("first");
                builder.withTenantId("from-customizer");
            };
        }
    }
}
