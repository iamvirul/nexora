package com.nexora.registry;

import com.nexora.spi.Capability;
import com.nexora.spi.CapabilityDescriptor;
import com.nexora.spi.CapabilityRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DefaultCapabilityRegistry implements CapabilityRegistry {

    private static final Logger log = LoggerFactory.getLogger(DefaultCapabilityRegistry.class);

    private final ConcurrentHashMap<String, CapabilityEntry> entries = new ConcurrentHashMap<>();
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    @Override
    public void register(CapabilityDescriptor descriptor, Capability capability) {
        Objects.requireNonNull(descriptor, "descriptor must not be null");
        Objects.requireNonNull(capability, "capability must not be null");
        lock.writeLock().lock();
        try {
            entries.put(descriptor.id(), new CapabilityEntry(descriptor, capability, Instant.now()));
            log.info("Registered capability id={}", descriptor.id());
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void deregister(String capabilityId) {
        Objects.requireNonNull(capabilityId, "capabilityId must not be null");
        lock.writeLock().lock();
        try {
            CapabilityEntry removed = entries.remove(capabilityId);
            if (removed != null) {
                log.info("Deregistered capability id={}", capabilityId);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public Optional<Capability> find(String capabilityId) {
        lock.readLock().lock();
        try {
            return Optional.ofNullable(entries.get(capabilityId)).map(CapabilityEntry::capability);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public Optional<CapabilityDescriptor> findDescriptor(String capabilityId) {
        lock.readLock().lock();
        try {
            return Optional.ofNullable(entries.get(capabilityId)).map(CapabilityEntry::descriptor);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public List<CapabilityDescriptor> listAll() {
        lock.readLock().lock();
        try {
            return entries.values().stream().map(CapabilityEntry::descriptor).toList();
        } finally {
            lock.readLock().unlock();
        }
    }

    private record CapabilityEntry(CapabilityDescriptor descriptor, Capability capability, Instant registeredAt) {}
}
