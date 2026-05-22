---
id: getting-started
title: Getting Started
sidebar_position: 2
---

# Getting Started

## Build

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
