---
id: nexora-planner-llm
title: nexora-planner-llm
sidebar_position: 8
---

# nexora-planner-llm

A reference planner plugin that turns a natural-language goal into a plan DAG with an LLM. It calls Anthropic, OpenAI, Gemini or any model on OpenRouter through the providers' official Java SDKs, validates every answer, and falls back to the rule-based planner when the model's answer cannot be used.

This page is the full reference. For setup per provider and when to use it, start with the [LLM Planner guide](../llm-planner/overview).

## Install

Build the plugin JAR and load it like any other plugin:

```bash
mvn package -pl nexora-planner-llm -am
# nexora-planner-llm/target/nexora-planner-llm-<version>-plugin.jar
```

```java
NexoraEngine engine = NexoraEngine.builder()
    .withPluginJar(Path.of("/opt/nexora/plugins/nexora-planner-llm-0.5.0-SNAPSHOT-plugin.jar"))
    .withPlugin(myCapabilities)
    .withStepDefinition(...)   // still used as the fallback plan
    .build();
```

With the CLI, list the JAR under `plugins` in `nexora.json`.

The JAR carries the Anthropic, OpenAI and Google Gen AI SDKs and their dependencies, which makes it large (about 140 MB, almost all of it the three SDKs). The plugin class loader keeps those libraries apart from the engine's own, so their versions never clash.

## Configure

The plugin is configured through environment variables, or the matching `nexora.llm.*` system properties, which take precedence. API keys are read only from the environment or a key file, never from system properties, which other processes on the machine can read.

| Variable | System property | Default | Meaning |
|----------|-----------------|---------|---------|
| `NEXORA_LLM_PROVIDER` | `nexora.llm.provider` | detected from the keys set | `anthropic`, `openai`, `openrouter` or `gemini` |
| `ANTHROPIC_API_KEY` / `OPENAI_API_KEY` / `OPENROUTER_API_KEY` / `GEMINI_API_KEY` | (none) | | The provider's API key |
| `NEXORA_LLM_API_KEY_FILE` | `nexora.llm.apiKeyFile` | | File holding the key, for example a mounted secret |
| `NEXORA_LLM_MODEL` | `nexora.llm.model` | `claude-opus-5-5` for Anthropic; required otherwise | Model to plan with. For OpenRouter, a routed name such as `anthropic/claude-opus-5.5` (OpenRouter writes versions with dots) |
| `NEXORA_LLM_MAX_TOKENS` | `nexora.llm.maxTokens` | `16000` | Output token limit |
| `NEXORA_LLM_EFFORT` | `nexora.llm.effort` | model default | Anthropic only: `low`, `medium`, `high`, `xhigh`, `max` |
| `NEXORA_LLM_TEMPERATURE` | `nexora.llm.temperature` | provider default | OpenAI, OpenRouter and Gemini only, `0` to `2` |
| `NEXORA_LLM_TIMEOUT` | `nexora.llm.timeout` | `60` seconds | Per attempt; seconds or ISO-8601 such as `PT30S` |
| `NEXORA_LLM_MAX_RETRIES` | `nexora.llm.maxRetries` | `2` | SDK retries for rate limits, overload and server errors |
| `NEXORA_LLM_CACHE_TTL` | `nexora.llm.cacheTtl` | `PT10M` | How long a plan is reused; `0` turns the cache off |
| `NEXORA_LLM_CACHE_MAX_ENTRIES` | `nexora.llm.cacheMaxEntries` | `1000` | Plans kept; the least recently used goes first |
| `NEXORA_LLM_BASE_URL` | `nexora.llm.baseUrl` | provider default | Point the SDK at a proxy or gateway |
| `NEXORA_LLM_PRIORITY` | `nexora.llm.priority` | `100` | Planner priority; higher is tried first |

Without `NEXORA_LLM_PROVIDER`, the first provider whose key is set is used, in the order Anthropic, OpenAI, OpenRouter, Gemini. With no provider and no key at all, the plugin activates without a planner and logs a `WARN`, and the engine keeps planning with its rules. A setting that is present but invalid (an unknown provider, a missing model or key, a temperature for Anthropic) fails plugin activation with the reason.

OpenRouter reserves credit for the full `max_tokens` before it answers, and thinking models spend part of it on reasoning. On a low-credit key, lower `NEXORA_LLM_MAX_TOKENS` (a plan answer needs a few hundred tokens), but leave thinking models a few thousand or the answer comes back `TRUNCATED`.

The `gemini` provider calls the Gemini Developer API with Google's Gen AI Java SDK and needs `GEMINI_API_KEY` and an explicit `NEXORA_LLM_MODEL`. Gemini is also reachable through `openrouter`.

Current Claude models reject `temperature`, so the Anthropic provider takes `effort` instead. When `NEXORA_LLM_PROVIDER=anthropic` is set without `ANTHROPIC_API_KEY`, the Anthropic SDK resolves credentials itself, for example from `ANTHROPIC_AUTH_TOKEN` or a saved `ant auth login` profile.

## How it plans

For each intent, the planner sends the model:

- a fixed system prompt that explains the plan format,
- the goal,
- every capability the intent's tenant may use, with its description and input and output parameters,
- the **names** of the values in the intent context. The values themselves are never sent.

The model answers in a JSON schema that each provider enforces as structured output (`output_config.format` for Anthropic, a strict `json_schema` response format for OpenAI and OpenRouter, `responseJsonSchema` with `application/json` for Gemini):

```json
{"steps": [
  {"id": "validate", "capabilityId": "validate_order", "dependsOn": [],
   "inputs": [{"name": "orderId", "source": "context", "value": "orderId", "stepId": null, "field": null}]},
  {"id": "charge", "capabilityId": "charge_card", "dependsOn": ["validate"],
   "inputs": [{"name": "orderId", "source": "context", "value": "orderId", "stepId": null, "field": null},
              {"name": "amount", "source": "literal", "value": "42.00", "stepId": null, "field": null}]},
  {"id": "receipt", "capabilityId": "send_receipt", "dependsOn": [],
   "inputs": [{"name": "chargeId", "source": "step", "value": null, "stepId": "charge", "field": "chargeId"}]}
]}
```

An input comes from the request context (`context`, by name), from another step's output (`step`, optionally one `field` of it), or is a literal string (`literal`). Steps without a dependency between them run in parallel.

Every answer is then checked before it runs. The planner refuses an answer whose:

- capability is not registered or not visible to the tenant,
- step ids are missing, invalid or used twice,
- dependency points at a missing step or forms a cycle,
- context binding names a value the request does not carry,
- required capability input is left unbound,
- step output `field` is not one the producing capability declares in its `outputParameters`,
- plan is empty or has more than 50 steps.

An input bound to another step's output also makes that step a dependency, since the output exists only once that step has run.

Declare `outputParameters` on your capability descriptors. The model sees them and binds the field a later step needs (`chargeId`) rather than the whole output; without them it has to guess the field names.

## Fallback

When the call fails, the model declines, the answer is cut off at the token limit, or the answer fails the checks above, the planner logs a `WARN` with the reason and throws `PlanningDeclinedException`. The engine's composite planner then tries the next planner, and in the end the rule-based planner built from your step definitions, so the execution still runs:

```
WARN LLM planner declined, falling back to the next planner provider=anthropic model=claude-opus-5-5
     goalHash=3f9a1c0e7b2d reason=INVALID_PLAN detail=step 'x' uses unknown capability 'launch_rocket'
```

The reason is one of `INVALID_PLAN`, `REJECTED` (bad key, permission, unknown model), `UNAVAILABLE` (rate limit, overload or server error after retries), `NETWORK`, `REFUSED` (including a Gemini safety block), `TRUNCATED`, `EMPTY`, or `CLIENT_ERROR` for an SDK failure no client recognised, which is also logged at `ERROR` with its stack trace. Log lines identify the goal by a hash rather than its text, but the `detail` can quote the model's answer, which may repeat words from the goal.

On models that support it (`claude-opus-5-5`, `claude-opus-5`, `claude-sonnet-5-5`, `claude-fable-5-1`), Anthropic requests also opt into server-side refusal fallback, so a request the model declines is answered by a fallback model instead.

## Cache

A valid plan is cached under `sha256` of the goal, the sorted capability ids and the sorted context names, so the same goal against the same capabilities is planned once per TTL. Context names are part of the key because the plan binds inputs to them. Invalid answers are never cached. The cache lives in memory, per engine.

## Latency

Planning runs on the thread that submits the intent, before the execution starts, so `engine.execute(...)` and `engine.submit(...)` wait for the model. Size `NEXORA_LLM_TIMEOUT` and `NEXORA_LLM_MAX_RETRIES` for that: the worst case is the timeout times one plus the retries, followed by the rule-based plan.

## Demo

`tests/llm-planner-demo` runs one goal through the rule-based planner and through the LLM planner loaded from its JAR, and prints the steps each plan ran with the inputs each step received:

```bash
mvn package -pl tests/llm-planner-demo -am
java -jar tests/llm-planner-demo/target/llm-planner-demo.jar --offline
ANTHROPIC_API_KEY=... java -jar tests/llm-planner-demo/target/llm-planner-demo.jar --goal "..."
```

`--offline` answers from a local stand-in for the Anthropic API, so it needs no key; the real SDK inside the plugin JAR still makes the call.
