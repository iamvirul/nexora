package me.virul.nexora.spring.boot.autoconfigure;

import java.util.List;
import me.virul.nexora.api.NexoraEngine;
import me.virul.nexora.persistence.ExecutionStore;
import me.virul.nexora.persistence.jdbc.JdbcExecutionStore;
import me.virul.nexora.planner.model.StepDefinition;
import me.virul.nexora.retry.ExponentialBackoffPolicy;
import me.virul.nexora.retry.RetryPolicy;
import me.virul.nexora.spi.NexoraPlugin;
import me.virul.nexora.spi.Planner;
import me.virul.nexora.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Creates a {@link NexoraEngine} from {@link NexoraProperties} and the Nexora beans the
 * application declares: every {@link NexoraPlugin}, {@link StepDefinition} and {@link Planner}
 * bean, plus at most one {@link ExecutionStore}, {@link Tracer} and {@link RetryPolicy}.
 *
 * <p>Backs off when the application defines its own {@code NexoraEngine}, and is switched off
 * entirely with {@code nexora.enabled=false}. The engine is closed with the application context,
 * which deactivates its plugins and closes its store and tracer.
 */
@AutoConfiguration
@ConditionalOnClass(NexoraEngine.class)
@ConditionalOnBooleanProperty(name = NexoraProperties.PREFIX + ".enabled", matchIfMissing = true)
@EnableConfigurationProperties(NexoraProperties.class)
public class NexoraAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(NexoraAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = NexoraProperties.PREFIX + ".persistence", name = "jdbc-url")
    public ExecutionStore nexoraExecutionStore(NexoraProperties properties) {
        NexoraProperties.Persistence persistence = properties.persistence();
        // The URL can carry credentials, so it is never logged; the JDBC store reports failures without it.
        return new JdbcExecutionStore(persistence.jdbcUrl(), persistence.pool().toPoolConfig());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = NexoraProperties.PREFIX + ".retry", name = "max-attempts")
    public RetryPolicy nexoraRetryPolicy(NexoraProperties properties) {
        NexoraProperties.Retry retry = properties.retry();
        ExponentialBackoffPolicy.Builder policy =
                ExponentialBackoffPolicy.builder().maxAttempts(retry.maxAttempts());
        if (retry.initialDelay() != null) {
            policy.initialDelay(retry.initialDelay());
        }
        if (retry.multiplier() != null) {
            policy.multiplier(retry.multiplier());
        }
        if (retry.maxDelay() != null) {
            policy.maxDelay(retry.maxDelay());
        }
        return policy.build();
    }

    @Bean
    @ConditionalOnMissingBean
    public NexoraEngine nexoraEngine(
            NexoraProperties properties,
            ObjectProvider<NexoraPlugin> plugins,
            ObjectProvider<StepDefinition> stepDefinitions,
            ObjectProvider<Planner> planners,
            ObjectProvider<ExecutionStore> executionStore,
            ObjectProvider<Tracer> tracer,
            ObjectProvider<RetryPolicy> retryPolicy,
            ObjectProvider<NexoraEngineBuilderCustomizer> customizers) {
        NexoraEngine.Builder builder = NexoraEngine.builder();
        applyProperties(builder, properties);

        // orderedStream() honours @Order, which sets plugin activation order for requiredPlugins.
        plugins.orderedStream().forEach(builder::withPlugin);
        List<StepDefinition> stepBeans = stepDefinitions.orderedStream().toList();
        stepBeans.forEach(builder::withStepDefinition);
        planners.orderedStream().forEach(builder::withPlanner);

        // getIfAvailable() fails on two candidates rather than silently picking neither.
        ExecutionStore store = executionStore.getIfAvailable();
        if (store != null) {
            builder.withExecutionStore(store);
        }
        tracer.ifAvailable(builder::withTracer);
        retryPolicy.ifAvailable(builder::withDefaultRetryPolicy);
        customizers.orderedStream().forEach(customizer -> customizer.customize(builder));

        NexoraEngine engine = builder.build();
        log.info(
                "NexoraEngine auto-configured plugins={} stepDefinitions={} persistence={} tenantId={}",
                engine.activePluginIds(),
                stepBeans.size(),
                store != null,
                engine.tenantScope().orElse("*"));
        return engine;
    }

    private static void applyProperties(NexoraEngine.Builder builder, NexoraProperties properties) {
        if (properties.tenantId() != null) {
            builder.withTenantId(properties.tenantId());
        }
        if (properties.defaultTimeout() != null) {
            builder.withDefaultTimeout(properties.defaultTimeout());
        }
        if (properties.defaultPlanDeadline() != null) {
            builder.withDefaultPlanDeadline(properties.defaultPlanDeadline());
        }
        if (properties.stepOutputMaxBytes() != null) {
            builder.withStepOutputMaxBytes(properties.stepOutputMaxBytes());
        }
        builder.withSagaEnabled(properties.sagaEnabled());
        builder.withWebhookSecret(properties.webhookSecret());
        properties.pluginJars().forEach(builder::withPluginJar);
    }
}
