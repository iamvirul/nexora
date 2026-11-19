package me.virul.nexora.spi;

import java.util.List;
import java.util.Optional;

/**
 * Thread-safe registry for capabilities.
 * This interface lives in the SPI so plugins can register additional capabilities
 * via PluginContext without depending on the registry implementation module.
 */
public interface CapabilityRegistry {

    /**
     * Registers a capability under its descriptor's id, replacing any capability already registered
     * under that id.
     *
     * @param descriptor describes the capability
     * @param capability the implementation
     */
    void register(CapabilityDescriptor descriptor, Capability capability);

    /**
     * Removes a capability. Does nothing when it is not registered.
     *
     * @param capabilityId the capability to remove
     */
    void deregister(String capabilityId);

    /**
     * Looks up a capability implementation.
     *
     * @param capabilityId the capability
     * @return the implementation, or empty when it is not registered
     */
    Optional<Capability> find(String capabilityId);

    /**
     * Looks up a capability descriptor.
     *
     * @param capabilityId the capability
     * @return the descriptor, or empty when it is not registered
     */
    Optional<CapabilityDescriptor> findDescriptor(String capabilityId);

    /**
     * Every registered capability, whatever its tenants.
     *
     * @return a snapshot of the descriptors
     */
    List<CapabilityDescriptor> listAll();

    /**
     * The capabilities {@code tenantId} may plan with; see {@link CapabilityDescriptor#tenantIds()}.
     *
     * @param tenantId the tenant
     * @return the descriptors visible to it
     */
    default List<CapabilityDescriptor> listFor(String tenantId) {
        return listAll().stream().filter(d -> d.visibleTo(tenantId)).toList();
    }

    /**
     * Whether {@code capabilityId} is registered and {@code tenantId} may invoke it.
     *
     * @param capabilityId the capability
     * @param tenantId     the tenant
     * @return {@code true} when both hold
     */
    default boolean isVisibleTo(String capabilityId, String tenantId) {
        return findDescriptor(capabilityId).map(d -> d.visibleTo(tenantId)).orElse(false);
    }
}
