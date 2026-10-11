package com.nexora.loader;

import com.nexora.spi.NexoraPlugin;
import java.net.URLClassLoader;
import java.util.concurrent.atomic.AtomicReference;

final class PluginState {

    private final NexoraPlugin plugin;
    private final URLClassLoader classLoader;
    private final AtomicReference<PluginLifecycle> lifecycle;

    PluginState(NexoraPlugin plugin, URLClassLoader classLoader, PluginLifecycle initial) {
        this.plugin = plugin;
        this.classLoader = classLoader;
        this.lifecycle = new AtomicReference<>(initial);
    }

    void transition(PluginLifecycle next) {
        lifecycle.set(next);
    }

    NexoraPlugin plugin() {
        return plugin;
    }

    URLClassLoader classLoader() {
        return classLoader;
    }

    PluginLifecycle lifecycle() {
        return lifecycle.get();
    }
}
