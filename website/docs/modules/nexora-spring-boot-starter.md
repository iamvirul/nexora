---
id: nexora-spring-boot-starter
title: nexora-spring-boot-starter
sidebar_position: 15
---

# nexora-spring-boot-starter

Auto-configures a `NexoraEngine` bean from `application.yml` and the Nexora beans your application declares. It targets Spring Boot 4. Import the `nexora-all` BOM (see [Getting started](../getting-started.md)) so the version resolves.

```xml
<dependency>
    <groupId>me.virul.nexora</groupId>
    <artifactId>nexora-spring-boot-starter</artifactId>
</dependency>
```

```yaml
nexora:
  tenant-id: acme                # optional: bind the engine to one tenant
  default-timeout: 5s            # per-step timeout when a step sets none
  default-plan-deadline: 30s     # whole-execution deadline when the intent sets none
  saga-enabled: true
  webhook-secret: ${NEXORA_WEBHOOK_SECRET}
  step-output-max-bytes: 65536
  plugin-jars:
    - /opt/nexora/plugins/payments.jar
  retry:                         # default retry policy; no retries without max-attempts
    max-attempts: 3
    initial-delay: 200ms
    multiplier: 2.0
    max-delay: 10s
  persistence:                   # JDBC store; no persistence without jdbc-url
    jdbc-url: ${NEXORA_JDBC_URL}
    pool:
      maximum-pool-size: 10
      minimum-idle: 2
```

Every key is optional; an unset key keeps the `NexoraEngine.Builder` default. An invalid value (a bad tenant id, a pool of 0 connections) fails application startup.

Beans are picked up automatically:

| Bean type | Effect |
|---|---|
| `NexoraPlugin` | Registered and activated, in `@Order` order (so required plugins go first) |
| `StepDefinition` | Added to the rule planner |
| `Planner` | Added ahead of the rule planner |
| `ExecutionStore` | Used instead of the store built from `nexora.persistence.jdbc-url` |
| `Tracer` | Used for step spans, for example an `OtelTracer` |
| `RetryPolicy` | Used instead of the policy built from `nexora.retry.*` |
| `NexoraEngineBuilderCustomizer` | Applied last, in `@Order` order, for anything without a property (such as a custom `Executor`) |

```java
@Configuration
class NexoraConfig {

    // The engine owns the plugin lifecycle; destroyMethod = "" stops Spring calling shutdown() a second time.
    @Bean(destroyMethod = "")
    NexoraPlugin paymentPlugin() {
        return new PaymentPlugin();
    }

    @Bean
    StepDefinition chargeStep() {
        return StepDefinition.builder("charge", "charge_card")
                .withMatcher(goal -> goal.contains("payment"))
                .withInput("amount", InputBinding.fromContext("intent.context.amount"))
                .build();
    }
}
```

Define your own `NexoraEngine` bean to take over completely, or set `nexora.enabled=false` to turn the starter off. The engine is closed with the application context.

With Spring Boot Actuator on the classpath, the `nexora` health component reports `NexoraEngine.readiness()`: UP only when persistence, its migrations, plugins and the executor are all UP, with each check, the active plugin ids and the bound tenant as details. Add it to the readiness probe with `management.endpoint.health.group.readiness.include=readinessState,nexora`, or turn it off with `management.health.nexora.enabled=false`.

`tests/spring-boot-demo` is a runnable order API built this way: a plugin and two step definitions as beans, H2 persistence, retry, and the `nexora` check in the readiness probe.

```bash
mvn install -DskipTests -DskipITs
java -jar tests/spring-boot-demo/target/spring-boot-demo.jar
curl -s localhost:8080/v1/orders -H 'Content-Type: application/json' -d '{"orderId":"ORD-1","amount":42.50}'
curl -s localhost:8080/actuator/health/readiness
```

