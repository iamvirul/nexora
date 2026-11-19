package me.virul.nexora.spi;

import org.slf4j.Logger;

/**
 * Services the engine provides to a plugin during initialization.
 * This is the only surface a plugin should use to interact with the engine.
 */
public interface PluginContext {

    /**
     * Retrieve a typed configuration value by key. Returns null if absent.
     *
     * @param <T>  the expected type
     * @param key  the configuration key
     * @param type the expected type
     * @return the value, or {@code null} when the key is absent
     * @throws ClassCastException if the value is not a {@code type}
     */
    <T> T getConfig(String key, Class<T> type);

    /**
     * Logger named {@code nexora.plugin.<plugin id>}.
     *
     * @return the logger
     */
    Logger logger();

    /**
     * Registry the plugin may use to register additional capabilities at runtime.
     *
     * @return the engine's capability registry
     */
    CapabilityRegistry capabilityRegistry();
}
