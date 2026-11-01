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
    private final String tenantId; // null = every capability, regardless of tenant restrictions

    public DefaultPlanningContext(CapabilityRegistry capabilityRegistry, Map<String, Object> config) {
        this(capabilityRegistry, config, null);
    }

    /** Plans with only the capabilities {@code tenantId} may use. */
    public DefaultPlanningContext(CapabilityRegistry capabilityRegistry, Map<String, Object> config, String tenantId) {
        this.capabilityRegistry = Objects.requireNonNull(capabilityRegistry);
        this.config = config == null ? Map.of() : Map.copyOf(config);
        this.tenantId = tenantId;
    }

    @Override
    public List<CapabilityDescriptor> availableCapabilities() {
        return tenantId == null ? capabilityRegistry.listAll() : capabilityRegistry.listFor(tenantId);
    }

    @Override
    public Map<String, Object> config() {
        return config;
    }
}
