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

```java
NexoraEngine engine = NexoraEngine.builder()
    .withPluginDirectory(Path.of("/etc/nexora/plugins"))  // loads all JARs in dir
    .build();

// Or load a single JAR:
engine.loadPlugin(Path.of("/opt/plugins/payment-plugin-1.2.0.jar"));
engine.activatePlugin("payment-plugin");
```

### Deactivating a plugin at runtime

```java
engine.deactivatePlugin("payment-plugin");
// All capabilities registered by payment-plugin are removed from the registry.
// In-flight steps against those capabilities complete with their current invocation.
```

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
3. Deploy the JAR to your plugin directory or pass the path to `engine.loadPlugin()`.

See [Writing Plugins](../writing-plugins) for a step-by-step guide.
