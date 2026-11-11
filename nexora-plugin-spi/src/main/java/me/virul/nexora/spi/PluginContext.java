package me.virul.nexora.spi;

import org.slf4j.Logger;

/**
 * Services the engine provides to a plugin during initialization.
 * This is the only surface a plugin should use to interact with the engine.
 */
public interface PluginContext {

    /** Retrieve a typed configuration value by key. Returns null if absent. */
    <T> T getConfig(String key, Class<T> type);

    /** Structured logger pre-populated with the plugin id. */
    Logger logger();

    /** Registry the plugin may use to register additional capabilities at runtime. */
    CapabilityRegistry capabilityRegistry();
}
