package com.nexora.planner.engine;

import com.nexora.spi.CapabilityDescriptor;
import com.nexora.spi.CapabilityRegistry;
import com.nexora.spi.PlanningContext;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class DefaultPlanningContext implements PlanningContext {

    private final CapabilityRegistry capabilityRegistry;
    private final Map<String, Object> config;

    public DefaultPlanningContext(CapabilityRegistry capabilityRegistry, Map<String, Object> config) {
        this.capabilityRegistry = Objects.requireNonNull(capabilityRegistry);
        this.config = config == null ? Map.of() : Map.copyOf(config);
    }

    @Override
    public List<CapabilityDescriptor> availableCapabilities() {
        return capabilityRegistry.listAll();
    }

    @Override
    public Map<String, Object> config() {
        return config;
    }
}
