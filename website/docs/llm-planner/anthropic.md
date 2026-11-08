---
id: anthropic
title: Anthropic (Claude)
sidebar_label: Anthropic
sidebar_position: 2
---

# Anthropic (Claude)

The `anthropic` provider plans with Claude models through the official Anthropic Java SDK. It is the only provider with a default model, so a key is all you need to start.

## Set up

1. Create an API key in the [Claude Console](https://console.anthropic.com/).
2. Export it where the engine runs:

   ```bash
   export ANTHROPIC_API_KEY=sk-ant-...
   ```

That is enough: with only `ANTHROPIC_API_KEY` set, the planner selects Anthropic and plans with `claude-opus-5-5`.

To be explicit, or to pick another model:

```bash
export NEXORA_LLM_PROVIDER=anthropic
export NEXORA_LLM_MODEL=claude-sonnet-5-5
```

Without `ANTHROPIC_API_KEY`, the SDK looks for credentials itself: `ANTHROPIC_AUTH_TOKEN`, or a profile saved by `ant auth login`. Set `NEXORA_LLM_PROVIDER=anthropic` to use those.

## Settings

| Variable | Default | Notes |
|----------|---------|-------|
| `ANTHROPIC_API_KEY` | | Or `NEXORA_LLM_API_KEY_FILE` pointing at a file holding it |
| `NEXORA_LLM_MODEL` | `claude-opus-5-5` | For example `claude-sonnet-5-5` or `claude-haiku-5-5` |
| `NEXORA_LLM_EFFORT` | the model's default | `low`, `medium`, `high`, `xhigh` or `max`. Controls how much the model reasons before answering |
| `NEXORA_LLM_TEMPERATURE` | | Not accepted: current Claude models reject it, so the plugin refuses to start with it set. Use effort instead |
| `NEXORA_LLM_BASE_URL` | Anthropic's API | For a proxy or gateway |

The [overview](overview#settings-shared-by-every-provider) lists the settings every provider shares.

## How it behaves

- **Structured output.** The plan is requested as JSON constrained by the plan schema (`output_config.format`), so the answer always parses.
- **Refusal fallback.** On `claude-opus-5-5`, `claude-opus-5`, `claude-sonnet-5-5` and `claude-fable-5-1`, requests opt into server-side refusal fallback: if the model declines, the API answers with a fallback model instead of a refusal. Other models do not send it.
- **Reasoning.** Current Claude models think before they answer. Thinking counts toward `NEXORA_LLM_MAX_TOKENS`, which is why the default is a generous `16000`. Lower `NEXORA_LLM_EFFORT` to make planning faster and cheaper.
- **Retries.** The SDK retries rate limits, overload and server errors up to `NEXORA_LLM_MAX_RETRIES` times before the planner falls back.

## Choosing a model

| Model | Pick it when |
|-------|--------------|
| `claude-opus-5-5` (default) | Goals are long, ambiguous or need many steps wired together |
| `claude-sonnet-5-5` | You want most of Opus's planning quality for less cost and latency |
| `claude-haiku-5-5` | Goals are short and the capability set is small, and cost matters most |

Start with the default, watch the `INVALID_PLAN` rate in your logs, and move to a smaller model if plans stay correct.

## Verified

Tested against the real Anthropic API with `claude-opus-5-5`:

- A two-step goal planned in about 5 seconds, binding the receipt's `chargeId` to the charge step's output.
- A five-step goal planned in about 9 seconds, with independent steps run in parallel.
- A goal no capability fits ("Book a flight to Paris") came back empty and fell back to the rule-based plan (`INVALID_PLAN`).
- An invalid key fell back with `REJECTED` and the API's 401 message.

## Troubleshooting

| You see | Do this |
|---------|---------|
| `REJECTED ... HTTP 401` | The key is wrong or revoked. Check `ANTHROPIC_API_KEY` |
| `REJECTED ... HTTP 404` or `400` naming the model | Check `NEXORA_LLM_MODEL` against the models your account can use |
| Plugin fails to start: "temperature is not supported" | Remove `NEXORA_LLM_TEMPERATURE`; set `NEXORA_LLM_EFFORT` instead |
| `TRUNCATED` | Raise `NEXORA_LLM_MAX_TOKENS`, or lower `NEXORA_LLM_EFFORT` so less of it goes to reasoning |
| `UNAVAILABLE ... HTTP 529` | The API is overloaded; the SDK already retried. Raise `NEXORA_LLM_MAX_RETRIES` or try later |
