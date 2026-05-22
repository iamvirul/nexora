---
id: writing-plugins
title: Writing a Plugin
sidebar_position: 5
---

# Writing a plugin

A plugin is a JAR that implements `NexoraPlugin` and declares itself in `META-INF/services/com.nexora.spi.NexoraPlugin`.

```java
public class MyPlugin implements NexoraPlugin {

    @Override
    public PluginDescriptor descriptor() {
        return new PluginDescriptor("my-plugin", "1.0.0", "Does stuff", List.of(), null);
    }

    @Override
    public void initialize(PluginContext ctx) {}

    @Override
    public List<CapabilityProvider> capabilityProviders() {
        return List.of(
            new CapabilityProvider() {
                public CapabilityDescriptor descriptor() {
                    return new CapabilityDescriptor(
                        "my_capability", "My Capability",
                        List.of(), List.of(), true, false
                    );
                }
                public Capability create(PluginContext ctx) {
                    return request -> CapabilityResult.success(Map.of("result", "ok"));
                }
            }
        );
    }

    @Override
    public void shutdown() {}
}
```

### Loading a plugin JAR at runtime

```java
engine.loadPlugin(Path.of("my-plugin.jar"), "my-plugin");
```

### Wire it directly without a JAR (useful in tests)

```java
NexoraEngine.builder().withPlugin(new MyPlugin()).build();
```
