package me.virul.nexora.spring.boot.autoconfigure;

import me.virul.nexora.api.NexoraEngine;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.health.autoconfigure.contributor.ConditionalOnEnabledHealthIndicator;
import org.springframework.context.annotation.Bean;

/**
 * Registers {@link NexoraHealthIndicator} as the {@code nexora} health component when Spring Boot
 * health support (Actuator) is on the classpath and a {@link NexoraEngine} bean exists. Turned off
 * with {@code management.health.nexora.enabled=false}.
 */
@AutoConfiguration(after = NexoraAutoConfiguration.class)
@ConditionalOnClass(name = NexoraHealthAutoConfiguration.HEALTH_INDICATOR_CLASS)
public class NexoraHealthAutoConfiguration {

    // By name: spring-boot-health is optional, and this class must load without it to be skipped.
    static final String HEALTH_INDICATOR_CLASS = "org.springframework.boot.health.contributor.HealthIndicator";

    /** The bean name minus its {@code HealthIndicator} suffix, {@code nexora}, names the health component. */
    @Bean
    @ConditionalOnBean(NexoraEngine.class)
    @ConditionalOnMissingBean(name = "nexoraHealthIndicator")
    @ConditionalOnEnabledHealthIndicator("nexora")
    public NexoraHealthIndicator nexoraHealthIndicator(NexoraEngine engine) {
        return new NexoraHealthIndicator(engine);
    }
}
