package com.nexora.planner.llm;

import com.nexora.core.plan.Plan;
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

/**
 * Plans the model already produced, so the same goal against the same capabilities does not pay
 * for a second call. Entries expire after the TTL; past the size bound the least recently used one
 * is dropped. Only valid plans are stored, and plans are immutable, so a hit can be shared.
 *
 * <p>The key is {@code sha256} over the goal, the sorted capability ids and the sorted context
 * names. Context names are part of it because the plan binds inputs to them.
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
                return size() > PlanCache.this.maxEntries;
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

    static String key(String goal, Collection<String> capabilityIds, Collection<String> contextNames) {
        StringBuilder material = new StringBuilder(goal).append('\n');
        capabilityIds.stream().sorted().forEach(id -> material.append(id).append(','));
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
