package com.nexora.spi;

import com.nexora.core.capability.CapabilityContract;
import com.nexora.core.tenant.TenantId;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * @param tenantIds the tenants allowed to plan and invoke this capability; empty means every tenant
 */
public record CapabilityDescriptor(
        String id,
        String description,
        List<ParameterDescriptor> inputParameters,
        List<ParameterDescriptor> outputParameters,
        boolean idempotent,
        boolean async,
        CapabilityContract contract,
        Set<String> tenantIds) {
    public CapabilityDescriptor {
        Objects.requireNonNull(id, "id must not be null");
        inputParameters = inputParameters == null ? List.of() : List.copyOf(inputParameters);
        outputParameters = outputParameters == null ? List.of() : List.copyOf(outputParameters);
        contract = contract == null ? CapabilityContract.none() : contract;
        tenantIds = tenantIds == null ? Set.of() : Set.copyOf(tenantIds);
        tenantIds.forEach(TenantId::requireValid);
    }

    /** A capability every tenant can use. */
    public CapabilityDescriptor(
            String id,
            String description,
            List<ParameterDescriptor> inputParameters,
            List<ParameterDescriptor> outputParameters,
            boolean idempotent,
            boolean async,
            CapabilityContract contract) {
        this(id, description, inputParameters, outputParameters, idempotent, async, contract, Set.of());
    }

    public CapabilityDescriptor(
            String id,
            String description,
            List<ParameterDescriptor> inputParameters,
            List<ParameterDescriptor> outputParameters,
            boolean idempotent,
            boolean async) {
        this(id, description, inputParameters, outputParameters, idempotent, async, CapabilityContract.none());
    }

    /** A copy restricted to {@code tenantIds}; an empty set opens it to every tenant again. */
    public CapabilityDescriptor withTenantIds(Set<String> tenantIds) {
        return new CapabilityDescriptor(
                id, description, inputParameters, outputParameters, idempotent, async, contract, tenantIds);
    }

    public boolean visibleTo(String tenantId) {
        return tenantIds.isEmpty() || tenantIds.contains(tenantId);
    }
}
