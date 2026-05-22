---
id: nexora-registry
title: nexora-registry
sidebar_position: 8
---

# nexora-registry

Thread-safe capability registry. Maps capability IDs to `Capability` instances at runtime.

## DefaultCapabilityRegistry

Uses a `ReadWriteLock`: concurrent reads (most invocations) proceed in parallel; writes (registration) briefly block new reads.

```java
// Capabilities registered via NexoraEngine.builder() end up here.
NexoraEngine engine = NexoraEngine.builder()
    .withPlugin(new PaymentPlugin())    // registers charge_card, refund_card, ...
    .build();
```

### Registering capabilities directly

You can bypass plugins and register capabilities inline during engine construction:

```java
NexoraEngine engine = NexoraEngine.builder()
    .withStepDefinition(new StepDefinition("notify", "send_email", ...))
    .build();

// Then register the capability the step references:
engine.register("send_email", new SendEmailCapability());
```

Or register before building:

```java
CapabilityRegistry registry = new DefaultCapabilityRegistry();
registry.register("send_email", new SendEmailCapability());
registry.register("send_sms",   new SendSmsCapability());
```

---

## Capability lookup

The executor resolves capability IDs at runtime immediately before each step executes. If a capability ID is not found, the step fails with `CapabilityNotFoundException`.

Always register all capabilities referenced by your step definitions before submitting an intent.

---

## Dynamic registration

Capabilities can be registered or replaced at runtime (while the engine is running). The read-write lock ensures consistency: in-flight executions complete against the old capability; new steps use the newly registered one.

This is the mechanism used by `PluginManager` when activating or deactivating a plugin.
