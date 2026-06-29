---
id: nexora-plugin-spi
title: nexora-plugin-spi
sidebar_position: 3
---

# nexora-plugin-spi

The plugin contract. Every capability and every plugin you write depends only on this module.

## Capability

The core unit of work. Implement this to add something the engine can invoke.

```java
public class SendEmailCapability implements Capability {

    @Override
    public CapabilityResult execute(CapabilityRequest request) {
        String to      = (String) request.inputs().get("email");
        String subject = (String) request.inputs().get("subject");

        emailClient.send(to, subject);

        return CapabilityResult.success(Map.of("sent", true));
    }
}
```

**Rules:**
- Implementations must be **thread-safe**. The engine calls `execute()` concurrently across independent steps.
- Return `CapabilityResult.success(output)` on success.
- Return `CapabilityResult.failure(exception)` or throw, both are handled.
- Return `CapabilityResult.success(output, amendments)` to reshape the remaining plan.

### CapabilityContract

Optionally override `contract()` to declare latency and error-rate SLAs. The engine monitors live metrics and reroutes to the fallback if breached.

```java
public class ChargeCardCapability implements Capability {

    @Override
    public CapabilityContract contract() {
        return CapabilityContract.builder()
            .expectedP99Latency(Duration.ofMillis(200))
            .maxErrorRate(0.01)          // 1%
            .fallbackCapabilityId("charge_card_v2")
            .build();
    }

    @Override
    public CapabilityResult execute(CapabilityRequest request) { ... }
}
```

---

## NexoraPlugin

Entry point for a plugin JAR. Discovered via `ServiceLoader` from `META-INF/services/com.nexora.spi.NexoraPlugin`.

```java
public class PaymentPlugin implements NexoraPlugin {

    @Override
    public PluginDescriptor descriptor() {
        return new PluginDescriptor("payment-plugin", "1.0.0", List.of());
    }

    @Override
    public void initialize(PluginContext context) {
        // acquire resources, read config
    }

    @Override
    public List<CapabilityProvider> capabilityProviders() {
        return List.of(
            CapabilityProvider.of("charge_card",  new ChargeCardCapability()),
            CapabilityProvider.of("charge_card_v2", new ChargeCardV2Capability())
        );
    }

    @Override
    public void shutdown() {
        // release resources; must not throw
    }
}
```

**Lifecycle:** `LOADED` → `initialize()` → `ACTIVE` → `shutdown()` → `INACTIVE`

### PluginDescriptor

```java
new PluginDescriptor(
    "my-plugin",              // unique plugin ID
    "2.1.0",                  // version string
    List.of("base-plugin")    // IDs of plugins that must be ACTIVE first
);
```

---

## CapabilityRequest

Passed to every `Capability.execute()` call.

| Method | Returns | Description |
|---|---|---|
| `inputs()` | `Map<String, Object>` | Resolved input values for this step |
| `context()` | `ExecutionContext` | Trace context, execution ID, prior step outputs |
| `stepId()` | `String` | The step being executed |
| `capabilityId()` | `String` | The capability ID being invoked |
