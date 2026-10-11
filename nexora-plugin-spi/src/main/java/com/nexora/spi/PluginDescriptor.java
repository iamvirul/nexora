package com.nexora.spi;

import java.util.List;
import java.util.Objects;

public record PluginDescriptor(
        String id,
        String version, // semver string, e.g. "1.2.3"
        String description,
        List<String> requiredPlugins, // plugin IDs that must be ACTIVE before this one initialises
        String minEngineVersion // engine must be >= this version; null = no constraint
        ) {
    public PluginDescriptor {
        Objects.requireNonNull(id, "plugin id must not be null");
        Objects.requireNonNull(version, "version must not be null");
        requiredPlugins = requiredPlugins == null ? List.of() : List.copyOf(requiredPlugins);
    }
}
