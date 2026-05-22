---
id: capability-contracts
title: Capability Contracts
sidebar_position: 3
---

# Capability contracts

Capabilities declare their expected operational behaviour. The engine monitors every call and reroutes traffic when a capability breaches its contract:

```java
new CapabilityDescriptor(
    "charge_card", "Charges the customer card",
    List.of(), List.of(), false, false,
    CapabilityContract.builder()
        .p99Latency(Duration.ofMillis(200))
        .maxErrorRate(0.05)
        .windowSize(20)
        .fallback("charge_card_fallback")
        .build()
)
```

If `charge_card` starts exceeding 200ms p99 or failing more than 5% of the time over the last 20 calls, the engine silently routes new calls to `charge_card_fallback`. When the primary recovers, traffic returns automatically. The caller sees a normal result either way.

Query live health at any time:

```java
NexoraEngine.HealthSnapshot health = NexoraEngine.HealthSnapshot.from(
    engine.capabilityHealth("charge_card"));
// health.sampleCount(), health.errorRate(), health.p99Latency()
```
