package me.virul.nexora.loader;

import java.util.Objects;

/** Outcome of a successful {@link PluginManager#reloadPlugin}. */
public record PluginReloadResult(String pluginId, String previousVersion, String newVersion) {
    public PluginReloadResult {
        Objects.requireNonNull(pluginId, "pluginId must not be null");
        Objects.requireNonNull(previousVersion, "previousVersion must not be null");
        Objects.requireNonNull(newVersion, "newVersion must not be null");
    }
}
