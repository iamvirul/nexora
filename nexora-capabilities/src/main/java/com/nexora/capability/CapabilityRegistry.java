package com.nexora.capability;

import java.util.HashMap;
import java.util.Map;

public class CapabilityRegistry {
    private final Map<String, Capability> registry = new HashMap<>();

    public void register(Capability capability) {
        registry.put(capability.name(), capability);
    }

    public Capability get(String name) {
        return registry.get(name);
    }
}
