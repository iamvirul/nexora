---
id: capability-contracts
title: Capability Contracts
sidebar_position: 3
---

# Capability contracts

> **Note**: Stateful circuit breaker options (`openDuration` and `probeInterval`) were added in v0.2.0.

Capabilities declare their expected operational behaviour. The engine monitors every call and reroutes traffic when a capability breaches its contract:

```java
new CapabilityDescriptor(
    "charge_card", "Charges the customer card",
    List.of(), List.of(), false, false,
    CapabilityContract.builder()
        .p99Latency(Duration.ofMillis(200))
        .maxErrorRate(0.05)
        .windowSize(20)
        .openDuration(Duration.ofSeconds(30))
        .probeInterval(Duration.ofSeconds(10))
        .fallback("charge_card_fallback")
        .build()
)
```

If `charge_card` starts exceeding 200ms p99 or failing more than 5% of the time over the last 20 calls, the engine silently opens the circuit and routes new calls to `charge_card_fallback`. The circuit remains `OPEN` for 30 seconds before transitioning to `HALF_OPEN`, where it probes the primary capability every 10 seconds. When the primary recovers, the circuit closes and traffic returns automatically. The caller sees a normal result either way.

Query live health at any time:

```java
NexoraEngine.HealthSnapshot health = NexoraEngine.HealthSnapshot.from(
    engine.capabilityHealth("charge_card"));
// health.state(), health.sampleCount(), health.errorRate(), health.p99Latency()
```
