package me.virul.nexora.planner.engine;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import me.virul.nexora.spi.CapabilityDescriptor;
import me.virul.nexora.spi.CapabilityRegistry;
import me.virul.nexora.spi.PlanningContext;

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
