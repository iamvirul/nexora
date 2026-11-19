package me.virul.nexora.spi;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import me.virul.nexora.core.capability.CapabilityContract;
import me.virul.nexora.core.tenant.TenantId;

/**
 * Describes a capability to planners and to the engine: what it is called, what it takes and returns,
 * the operational contract it is held to, and which tenants may use it.
 *
 * @param id               the capability id that plan steps refer to
 * @param description      what the capability does, in plain language; planners that use a language
 *                         model show it to the model
 * @param inputParameters  the inputs the capability reads; never null and immutable
 * @param outputParameters the fields of the output it returns; never null and immutable
 * @param idempotent       whether calling it twice with the same idempotency key has the same effect as
 *                         calling it once. Declared for planners and tooling; the engine calls the
 *                         capability the same way either way
 * @param async            whether the capability completes its work asynchronously. Declared for
 *                         planners and tooling; the engine calls the capability the same way either way
 * @param contract         the operational contract the engine enforces; {@code null} becomes
 *                         {@link CapabilityContract#none()}
 * @param tenantIds        the tenants allowed to plan and invoke this capability; empty means every tenant
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

    /**
     * Validates the descriptor and copies its collections, treating {@code null} as empty.
     *
     * @throws NullPointerException     if {@code id} is null
     * @throws IllegalArgumentException if a tenant id is not valid
     */
    public CapabilityDescriptor {
        Objects.requireNonNull(id, "id must not be null");
        inputParameters = inputParameters == null ? List.of() : List.copyOf(inputParameters);
        outputParameters = outputParameters == null ? List.of() : List.copyOf(outputParameters);
        contract = contract == null ? CapabilityContract.none() : contract;
        tenantIds = tenantIds == null ? Set.of() : Set.copyOf(tenantIds);
        tenantIds.forEach(TenantId::requireValid);
    }

    /**
     * A capability every tenant can use.
     *
     * @param id               the capability id
     * @param description      what the capability does
     * @param inputParameters  the inputs it reads
     * @param outputParameters the fields of its output
     * @param idempotent       whether repeated calls with one idempotency key act once
     * @param async            whether it completes its work asynchronously
     * @param contract         the operational contract, or {@code null} for none
     */
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

    /**
     * A capability every tenant can use, held to no operational contract.
     *
     * @param id               the capability id
     * @param description      what the capability does
     * @param inputParameters  the inputs it reads
     * @param outputParameters the fields of its output
     * @param idempotent       whether repeated calls with one idempotency key act once
     * @param async            whether it completes its work asynchronously
     */
    public CapabilityDescriptor(
            String id,
            String description,
            List<ParameterDescriptor> inputParameters,
            List<ParameterDescriptor> outputParameters,
            boolean idempotent,
            boolean async) {
        this(id, description, inputParameters, outputParameters, idempotent, async, CapabilityContract.none());
    }

    /**
     * A copy restricted to {@code tenantIds}; an empty set opens it to every tenant again.
     *
     * @param tenantIds the tenants of the copy, or {@code null} or empty for every tenant
     * @return the copy; every other component is unchanged
     * @throws IllegalArgumentException if a tenant id is not valid
     */
    public CapabilityDescriptor withTenantIds(Set<String> tenantIds) {
        return new CapabilityDescriptor(
                id, description, inputParameters, outputParameters, idempotent, async, contract, tenantIds);
    }

    /**
     * Whether a tenant may plan with and invoke this capability.
     *
     * @param tenantId the tenant
     * @return {@code true} when {@link #tenantIds()} is empty or contains it
     */
    public boolean visibleTo(String tenantId) {
        return tenantIds.isEmpty() || tenantIds.contains(tenantId);
    }
}
