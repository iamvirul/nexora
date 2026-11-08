---
id: overview
title: LLM Planner
sidebar_label: Overview
sidebar_position: 1
---

# LLM Planner

The LLM planner turns a goal written in plain language, such as *"Charge order ORD-42 and email the customer a receipt"*, into a plan of steps that the engine runs with your capabilities. It ships as the `nexora-planner-llm` plugin and works with four providers:

| Provider | Page | Reaches | Default model | SDK |
|----------|------|---------|---------------|-----|
| `anthropic` | [Anthropic](anthropic) | Claude models | `claude-opus-5-5` | Official Anthropic Java SDK |
| `openai` | [OpenAI](openai) | OpenAI models | none, set `NEXORA_LLM_MODEL` | Official OpenAI Java SDK |
| `openrouter` | [OpenRouter](openrouter) | Hundreds of models from many vendors | none, set `NEXORA_LLM_MODEL` | Official OpenAI Java SDK, pointed at OpenRouter |
| `gemini` | [Gemini](gemini) | Google Gemini models | none, set `NEXORA_LLM_MODEL` | Official Google Gen AI Java SDK |

Not sure whether it fits your workload? See [Use cases](use-cases).

## How it works

1. An intent arrives. The engine asks the LLM planner first, ahead of the rule-based planner.
2. The planner sends the model the goal, the capabilities the intent's tenant may use, and the **names** of the values in the intent context. The values themselves never leave your process.
3. The model answers with a plan as structured JSON. Each provider is asked to enforce the plan's JSON schema.
4. The planner checks the plan before anything runs: every capability exists, step ids are unique, dependencies form no cycle, each required input is bound, and each binding points at a context value or step output that will exist.
5. A valid plan runs and is cached. Anything else (a refusal, a timeout, an invalid plan, a bad key) logs a `WARN` with the reason, and the engine runs the rule-based plan instead.

The model can only arrange your capabilities. It cannot add a capability, call anything you did not register, or reach a capability another tenant owns.

## Quick start

1. Build the plugin JAR:

   ```bash
   mvn package -pl nexora-planner-llm -am
   ```

2. Load it with your capabilities, and keep your step definitions, which become the fallback plan:

   ```java
   NexoraEngine engine = NexoraEngine.builder()
       .withPlugin(myCapabilities)
       .withPluginJar(Path.of("plugins/nexora-planner-llm-0.5.0-plugin.jar"))
       .withStepDefinition(...)
       .build();
   ```

   With the CLI, list the JAR under `plugins` in `nexora.json`.

3. Pick a provider and set its key. For example:

   ```bash
   export ANTHROPIC_API_KEY=...
   ```

4. Try it without writing code. The demo runs one goal through the rule-based planner and the LLM planner side by side:

   ```bash
   mvn package -pl tests/llm-planner-demo -am
   java -jar tests/llm-planner-demo/target/llm-planner-demo.jar --offline   # no key needed
   java -jar tests/llm-planner-demo/target/llm-planner-demo.jar             # uses your provider
   ```

## Choosing a provider

- **Start with Anthropic** if you have no preference. It is the only provider with a default model, it has server-side refusal fallback, and it is the most tested path here.
- **Use OpenAI** if your organisation already has an OpenAI account and approved models.
- **Use OpenRouter** to try several models behind one key, to compare cost and quality, or to reach models from vendors that have no Java SDK of their own.
- **Use Gemini** if you are on Google Cloud or already pay for Gemini, and want to call Google directly rather than through OpenRouter.

Whichever you pick, the planner behaves the same: same prompt, same schema, same checks, same fallback. You can switch providers by changing environment variables, with no code change.

## Settings shared by every provider

| Variable | Default | Meaning |
|----------|---------|---------|
| `NEXORA_LLM_PROVIDER` | detected from the keys set | `anthropic`, `openai`, `openrouter` or `gemini` |
| `NEXORA_LLM_MODEL` | provider default | Model to plan with |
| `NEXORA_LLM_MAX_TOKENS` | `16000` | Output token limit, including any reasoning |
| `NEXORA_LLM_TIMEOUT` | `60` seconds | Per attempt |
| `NEXORA_LLM_MAX_RETRIES` | `2` | SDK retries for rate limits, overload and server errors |
| `NEXORA_LLM_CACHE_TTL` | `PT10M` | How long a plan is reused; `0` turns the cache off |
| `NEXORA_LLM_PRIORITY` | `100` | Planner priority; higher is tried first |
| `NEXORA_LLM_API_KEY_FILE` | | File holding the key, for example a mounted secret |

Each setting also has a `nexora.llm.*` system property, which takes precedence. API keys are never read from system properties. The full list is in the [module reference](../modules/nexora-planner-llm).

## Reading the logs

Every fallback logs one `WARN` line with a reason:

```
WARN LLM planner declined, falling back to the next planner provider=anthropic model=claude-opus-5-5
     goalHash=3f9a1c0e7b2d reason=INVALID_PLAN detail=the model returned no steps
```

| Reason | Usually means | What to do |
|--------|---------------|------------|
| `INVALID_PLAN` | The plan failed a check, or no capability fits the goal | Improve capability descriptions; declare `outputParameters` |
| `REJECTED` | Bad key, no permission, unknown model, or no credit | Check the key, the model name and the account |
| `UNAVAILABLE` | Rate limited, overloaded or a server error, after retries | Wait, raise retries, or check the provider's status page |
| `NETWORK` | No connection, or the request timed out | Check egress, proxy and `NEXORA_LLM_TIMEOUT` |
| `REFUSED` | The model or a safety filter declined | Reword the goal or capability descriptions |
| `TRUNCATED` | The answer hit `max_tokens` | Raise `NEXORA_LLM_MAX_TOKENS` |
| `EMPTY` | The response had no text | Usually transient; retry |
| `CLIENT_ERROR` | An SDK failure nothing recognised (also logged at `ERROR`) | Read the stack trace; report it if it repeats |

Logs carry a hash of the goal, never the goal itself.
