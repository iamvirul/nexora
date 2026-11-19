package me.virul.nexora.registry;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import me.virul.nexora.spi.Capability;
import me.virul.nexora.spi.CapabilityDescriptor;
import me.virul.nexora.spi.CapabilityRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link CapabilityRegistry} backed by a {@link ConcurrentHashMap}. Every operation is a single
 * atomic map operation, so lookups never lock and never wait for each other. {@link #listAll()} is a
 * weakly consistent snapshot: it may or may not include a capability registered while it runs, and
 * every descriptor it returns belongs to a capability that was registered.
 */
public final class DefaultCapabilityRegistry implements CapabilityRegistry {

    private static final Logger log = LoggerFactory.getLogger(DefaultCapabilityRegistry.class);

    private final ConcurrentHashMap<String, CapabilityEntry> entries = new ConcurrentHashMap<>();

    @Override
    public void register(CapabilityDescriptor descriptor, Capability capability) {
        Objects.requireNonNull(descriptor, "descriptor must not be null");
        Objects.requireNonNull(capability, "capability must not be null");
        entries.put(descriptor.id(), new CapabilityEntry(descriptor, capability, Instant.now()));
        log.info("Registered capability id={}", descriptor.id());
    }

    @Override
    public void deregister(String capabilityId) {
        Objects.requireNonNull(capabilityId, "capabilityId must not be null");
        if (entries.remove(capabilityId) != null) {
            log.info("Deregistered capability id={}", capabilityId);
        }
    }

    @Override
    public Optional<Capability> find(String capabilityId) {
        return Optional.ofNullable(entries.get(capabilityId)).map(CapabilityEntry::capability);
    }

    @Override
    public Optional<CapabilityDescriptor> findDescriptor(String capabilityId) {
        return Optional.ofNullable(entries.get(capabilityId)).map(CapabilityEntry::descriptor);
    }

    @Override
    public List<CapabilityDescriptor> listAll() {
        return entries.values().stream().map(CapabilityEntry::descriptor).toList();
    }

    private record CapabilityEntry(CapabilityDescriptor descriptor, Capability capability, Instant registeredAt) {}
}
