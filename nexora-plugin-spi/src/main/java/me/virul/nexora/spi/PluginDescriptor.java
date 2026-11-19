package me.virul.nexora.spi;

import java.util.List;
import java.util.Objects;

/**
 * Identifies a plugin and what it needs to start.
 *
 * @param id               the plugin id, unique among loaded plugins
 * @param version          the plugin version, a semver string such as {@code "1.2.3"}
 * @param description      what the plugin provides, in plain language
 * @param requiredPlugins  the ids of the plugins that must be ACTIVE before this one initialises;
 *                         never null and immutable
 * @param minEngineVersion the oldest engine version the plugin supports, or {@code null} for no
 *                         constraint. Declared for compatibility; the engine does not check it yet
 */
public record PluginDescriptor(
        String id, String version, String description, List<String> requiredPlugins, String minEngineVersion) {

    /**
     * Validates the descriptor and copies its required plugins, treating {@code null} as empty.
     *
     * @throws NullPointerException if {@code id} or {@code version} is null
     */
    public PluginDescriptor {
        Objects.requireNonNull(id, "plugin id must not be null");
        Objects.requireNonNull(version, "version must not be null");
        requiredPlugins = requiredPlugins == null ? List.of() : List.copyOf(requiredPlugins);
    }
}
