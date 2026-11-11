package me.virul.nexora.spring.boot.autoconfigure;

import me.virul.nexora.api.NexoraEngine;

/**
 * Adjusts the auto-configured engine's builder before it is built, for settings that have no
 * {@code nexora.*} property, such as a custom {@link java.util.concurrent.Executor}.
 *
 * <p>Every customizer bean is applied, in {@link org.springframework.core.annotation.Order} order,
 * after the properties and the plugin, step definition, planner, store, tracer and retry policy
 * beans. A customizer can replace any single-valued setting, such as the store or tenant; plugins,
 * step definitions and planners it adds come after the beans.
 */
@FunctionalInterface
public interface NexoraEngineBuilderCustomizer {

    void customize(NexoraEngine.Builder builder);
}
