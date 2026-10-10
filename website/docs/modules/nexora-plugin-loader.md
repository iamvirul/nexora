---
id: nexora-plugin-loader
title: nexora-plugin-loader
sidebar_position: 12
---

# nexora-plugin-loader

Plugin class isolation and lifecycle management. Each plugin runs in its own `PluginClassLoader` so plugins cannot interfere with each other or the host application.

## PluginManager

Manages the full lifecycle: load → activate → deactivate → unload.

```
LOADED → activate() → ACTIVE → deactivate() → INACTIVE
```

### Loading a plugin from a JAR

At startup, `build()` loads and activates each JAR after any `withPlugin()` plugins, in the order added. List a plugin's required plugins first. If a plugin fails to initialize, `build()` throws `PluginInitializationException`.

```java
NexoraEngine engine = NexoraEngine.builder()
    .withPluginJar(Path.of("/etc/nexora/plugins/payment-plugin-1.2.0.jar"))
    .build();
```

On a running engine, `loadPlugin` loads the JAR and activates the plugin with the given id:

```java
engine.loadPlugin(Path.of("/opt/plugins/fraud-plugin-2.0.0.jar"), "fraud-plugin");
```

### Deactivation

All active plugins are deactivated when the engine shuts down (`engine.shutdown()` or `close()`). For each plugin, its capabilities and planners are removed from the registry, `shutdown()` is called, and its class loader is closed. There is no public `NexoraEngine` API to deactivate a single plugin at runtime.

---

## PluginClassLoader

Each plugin gets a child `URLClassLoader` that:
- Loads classes from the plugin JAR first (child-first strategy)
- Falls back to the host classloader for `com.nexora.spi.*` and `java.*`

This ensures the plugin's dependencies (e.g. its own version of a HTTP client) do not conflict with other plugins or the host.

---

## Dependency ordering

A plugin's `PluginDescriptor.requiredPlugins()` lists IDs of plugins that must be `ACTIVE` before this plugin can be activated. `PluginManager` enforces this at `activatePlugin()` time.

```java
new PluginDescriptor(
    "advanced-payment",
    "1.0.0",
    List.of("base-payment")   // base-payment must be ACTIVE first
);
```

---

## Writing a plugin JAR

1. Create a JAR with a class that implements `NexoraPlugin`.
2. Add a service descriptor file: `META-INF/services/com.nexora.spi.NexoraPlugin` containing the fully qualified class name.
3. Pass the JAR path to `NexoraEngine.Builder.withPluginJar()` at startup, or to `engine.loadPlugin(path, pluginId)` on a running engine.

See [Writing Plugins](../writing-plugins) for a step-by-step guide.
