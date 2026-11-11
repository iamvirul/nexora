---
id: getting-started
title: Getting Started
sidebar_position: 2
---

# Getting Started

## Add Nexora to your project

From 0.5.0 the library modules are on Maven Central under the group `me.virul.nexora`. Import the BOM, `nexora-all`, so every Nexora module resolves to the same version, then add `nexora-api`, which brings in the engine:

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>me.virul.nexora</groupId>
            <artifactId>nexora-all</artifactId>
            <version>0.5.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <dependency>
        <groupId>me.virul.nexora</groupId>
        <artifactId>nexora-api</artifactId>
    </dependency>
</dependencies>
```

Gradle (Kotlin DSL):

```kotlin
dependencies {
    implementation(platform("me.virul.nexora:nexora-all:0.5.0"))
    implementation("me.virul.nexora:nexora-api")
}
```

Add `nexora-tracing-otel` for OpenTelemetry tracing. A plugin built as its own JAR needs only `nexora-plugin-spi`, as `provided` (Gradle: `compileOnly`), since the engine supplies it at runtime. The Java packages are `me.virul.nexora.*`. [nexora-api](./modules/nexora-api.md) shows how to build and run an engine.

The CLI (`nexora.jar`) and the LLM planner plugin JAR are not on Maven Central; download them from the [GitHub Releases](https://github.com/iamvirul/nexora/releases), or build them from source as below.

## Build from source

You can build the project using Maven:

```bash
mvn install -DskipTests
```

The CLI fat JAR ends up at `nexora-cli/target/nexora.jar`.

## Try it immediately

No config file needed. The `demo` command showcases all three differentiating features in a single run:

```bash
java -jar nexora-cli/target/nexora.jar demo
```

You should see output similar to this:

```
Nexora Feature Demo
===================

Features demonstrated:
  1. Pluggable planner SPI  - rule-based planner wired via CompositePlanner
  2. Reactive plan amendment - validate_order injects audit_log at runtime
  3. Capability contracts    - charge_card declares p99 SLA + fallback

Initial DAG (from planner):
  validate_order --+
                   +--> charge_card --> send_receipt
  fetch_inventory -+

  validate_order will amend the plan at runtime, injecting:
  --> audit_log (runs after validate_order, before send_receipt)

Execution:
  ✓ validate_order          37ms
  ~ plan amended: ADD_STEP   -> audit_log
  ~ plan amended: MODIFY_INPUT -> send_receipt
  ✓ fetch_inventory         54ms
  ✓ audit_log               16ms
  ✓ charge_card             86ms
  ✓ send_receipt            22ms

Result:   COMPLETED
Steps:    5 executed

Contract health (charge_card):
  samples=1  error-rate=0%  p99=86ms
```

The plan started with 4 steps and finished with 5. `validate_order` injected `audit_log` mid-run. `charge_card` was monitored against its declared p99=200ms SLA throughout.
