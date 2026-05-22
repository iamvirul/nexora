---
id: overview
title: Modules Layout
sidebar_position: 1
---

# Module layout

Nexora is broken down into a number of modules. Dependencies always point inward. `nexora-cli` knows about `nexora-api`. `nexora-api` knows about `nexora-runtime`. Neither knows anything about the CLI.

```
nexora-core           Domain types: Intent, Plan, Step, PlanAmendment, ExecutionContext
nexora-plugin-spi     Plugin contract: NexoraPlugin, Capability, Planner, CapabilityRegistry
nexora-registry       DefaultCapabilityRegistry (thread-safe, read-write locked)
nexora-tracing        Tracer/Span interfaces + no-op implementation
nexora-retry          RetryPolicy, ExponentialBackoffPolicy with jitter
nexora-event          ExecutionEvent sealed hierarchy, InProcessEventBus
nexora-executor       DAG scheduler with amendment support, interceptor pipeline, contract monitor
nexora-plugin-loader  PluginClassLoader, PluginManager, lifecycle FSM
nexora-planner        CompositePlanner, RulePlanner, StepDefinition, PlanRegistry
nexora-runtime        ExecutionEngine (ties planner + scheduler together)
nexora-api            NexoraEngine public facade, builder, NexoraObservability
nexora-cli            PicoCLI command-line interface + ObserveCommand HTTP server
```
