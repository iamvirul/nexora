package com.nexora.loader;

import com.nexora.spi.CapabilityRegistry;
import com.nexora.spi.PluginContext;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class DefaultPluginContext implements PluginContext {

    private final String pluginId;
    private final Map<String, Object> config;
    private final CapabilityRegistry capabilityRegistry;
    private final Logger logger;

    DefaultPluginContext(String pluginId, Map<String, Object> config, CapabilityRegistry capabilityRegistry) {
        this.pluginId = pluginId;
        this.config = config;
        this.capabilityRegistry = capabilityRegistry;
        this.logger = LoggerFactory.getLogger("nexora.plugin." + pluginId);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getConfig(String key, Class<T> type) {
        Object value = config.get(key);
        if (value == null) return null;
        return type.cast(value);
    }

    @Override
    public Logger logger() {
        return logger;
    }

    @Override
    public CapabilityRegistry capabilityRegistry() {
        return capabilityRegistry;
    }
}
