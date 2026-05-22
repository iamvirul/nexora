---
id: intro
title: Introduction
sidebar_position: 1
---

# Nexora

Nexora is a Java execution engine that turns a high-level goal into a set of steps and runs them. You tell it what you want to happen, and it figures out the order, runs independent steps in parallel, and gives you back a result.

The idea is that you shouldn't have to hard-code execution logic. You declare capabilities (things the system can do), define which steps map to which goal keywords, and Nexora handles the rest: planning, scheduling, retrying on failure, and tracing what happened.

### What sets Nexora apart?

- **Pluggable planner SPI** - the planner itself is a plugin. Swap in an LLM, a constraint solver, or your own rule engine. The built-in keyword matcher is just the default.
- **Reactive plan amendment** - a step can reshape the remaining plan based on what it produced. Inject new steps, skip pending ones, or override inputs for downstream steps, all at runtime without touching the planner.
- **Capability contracts with automatic fallback** - capabilities declare their expected latency and error rate. The engine monitors live call metrics and silently reroutes to a fallback capability when the primary starts breaching its contract.

### Requirements

- Java 21
- Maven 3.9+
