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
                        ↑  │
                        └──┘ reloadPlugin(): ACTIVE (new version)
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

### Hot reload

A JAR plugin can be swapped for a new version on a running engine, without a restart:

```java
PluginReloadResult result =
    engine.reloadPlugin("payments", Path.of("/opt/nexora/plugins/payments-1.3.0.jar"));
// result.previousVersion() = "1.2.0", result.newVersion() = "1.3.0"
```

The new JAR is loaded under a fresh `PluginClassLoader` and fully initialised (`initialize()`, then every capability and planner provider) before anything changes, so for a moment both versions are initialised side by side. Its capabilities and planners then replace the old ones, capabilities the new version no longer provides are removed, and every new invocation uses the new version.

Steps already running on the old version finish on it. Every capability and planner from a JAR plugin is registered behind a lease on its version; the old version's `shutdown()` runs, and its class loader closes, when the last invocation holding a lease returns. An invocation that looked a capability up just before the swap but reaches it after the old version was released is handed to the version registered under the same id now.

The reload is refused, and nothing changes, when the new JAR:

- cannot be read or contains no `NexoraPlugin`,
- declares a different plugin id,
- needs a required plugin that is not `ACTIVE`,
- fails in `initialize()` or in a provider (its `shutdown()` is then called and its class loader closed),
- provides a capability id another plugin already registered.

The old version keeps serving, a `PluginReloadFailedEvent` is published with the reason, and `PluginInitializationException` is thrown. On success a `PluginReloadedEvent` carries both version strings.

`engine.reloadPlugin` requires an `ExecutionStore` and throws `IllegalStateException` without one, as it does for a plugin that is not `ACTIVE` or was registered in code with `withPlugin()`. The `nexora plugins reload` command does the same through a running observe server; see [CLI](../cli).

Write each new version to a new file. The old version may still load classes from its JAR until it is released, so overwriting that file in place can break the steps still running on it.

### Deactivation

All active plugins are deactivated when the engine shuts down (`engine.shutdown()` or `close()`). For each plugin, its capabilities and planners are removed from the registry. A JAR plugin's `shutdown()` runs and its class loader closes once the invocations still running on it return; a plugin registered in code is shut down at once. There is no public `NexoraEngine` API to deactivate a single plugin at runtime.

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
