package me.virul.nexora.planner.llm;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import me.virul.nexora.core.plan.Plan;
import me.virul.nexora.spi.CapabilityDescriptor;
import me.virul.nexora.spi.ParameterDescriptor;

/**
 * Plans the model already produced, so the same goal against the same capabilities does not pay
 * for a second call. Entries expire after the TTL; past the size bound the least recently used one
 * is dropped. Only valid plans are stored, and plans are immutable, so a hit can be shared.
 *
 * <p>The key is {@code sha256} over the goal, the sorted capability {@link #signature signatures}
 * and the sorted context names. A signature carries the capability's inputs and outputs as well as
 * its id, so a plugin reloaded with a changed contract never gets a plan checked against the old
 * one. Context names are part of the key because the plan binds inputs to them.
 */
final class PlanCache {

    private final Duration ttl;
    private final int maxEntries;
    private final Clock clock;
    private final Map<String, Entry> entries;

    private record Entry(Plan plan, Instant expiresAt) {}

    PlanCache(Duration ttl, int maxEntries, Clock clock) {
        this.ttl = Objects.requireNonNull(ttl, "ttl must not be null");
        this.maxEntries = maxEntries;
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        // Access order plus removeEldestEntry is an LRU bounded at maxEntries.
        this.entries = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
                return this.size() > PlanCache.this.maxEntries;
            }
        };
    }

    boolean enabled() {
        return !ttl.isZero();
    }

    synchronized Optional<Plan> get(String key) {
        Entry entry = entries.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (!clock.instant().isBefore(entry.expiresAt())) {
            entries.remove(key);
            return Optional.empty();
        }
        return Optional.of(entry.plan());
    }

    synchronized void put(String key, Plan plan) {
        if (enabled()) {
            entries.put(key, new Entry(plan, clock.instant().plus(ttl)));
        }
    }

    synchronized int size() {
        return entries.size();
    }

    /**
     * The parts of a capability a plan is checked against: its id, its input names with whether each
     * is required, and its output names, each list sorted.
     */
    static String signature(CapabilityDescriptor capability) {
        StringBuilder signature = new StringBuilder(capability.id()).append('(');
        capability.inputParameters().stream()
                .map(p -> p.name() + (p.required() ? "!" : "?"))
                .sorted()
                .forEach(input -> signature.append(input).append(';'));
        signature.append(")->(");
        capability.outputParameters().stream()
                .map(ParameterDescriptor::name)
                .sorted()
                .forEach(output -> signature.append(output).append(';'));
        return signature.append(')').toString();
    }

    static String key(String goal, Collection<String> capabilitySignatures, Collection<String> contextNames) {
        StringBuilder material = new StringBuilder(goal).append('\n');
        capabilitySignatures.stream()
                .sorted()
                .forEach(signature -> material.append(signature).append(','));
        material.append('\n');
        contextNames.stream().sorted().forEach(name -> material.append(name).append(','));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
    }
}
