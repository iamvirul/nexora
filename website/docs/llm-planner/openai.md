---
id: openai
title: OpenAI
sidebar_label: OpenAI
sidebar_position: 3
---

# OpenAI

The `openai` provider plans with OpenAI models through the official OpenAI Java SDK, using the Chat Completions API.

## Set up

1. Create an API key in the [OpenAI platform](https://platform.openai.com/api-keys).
2. Export it, and name the model to plan with. OpenAI model names change often, so there is no default:

   ```bash
   export OPENAI_API_KEY=sk-...
   export NEXORA_LLM_MODEL="your-openai-model-id"   # replace with a model your account can use
   ```

With only `OPENAI_API_KEY` set, the planner selects OpenAI; with several keys set, add `NEXORA_LLM_PROVIDER=openai` to choose it.

## Settings

| Variable | Default | Notes |
|----------|---------|-------|
| `OPENAI_API_KEY` | | Or `NEXORA_LLM_API_KEY_FILE` |
| `NEXORA_LLM_MODEL` | required | Pick a model that supports structured outputs (JSON schema) |
| `NEXORA_LLM_TEMPERATURE` | the model's default | `0` to `2`. Some reasoning models accept only their default; leave it unset for those |
| `NEXORA_LLM_EFFORT` | | Not accepted for OpenAI; the plugin refuses to start with it set |
| `NEXORA_LLM_BASE_URL` | OpenAI's API | For a proxy, or an Azure OpenAI or other OpenAI-compatible endpoint |

The [overview](overview#settings-shared-by-every-provider) lists the settings every provider shares.

## How it behaves

- **Structured output.** The plan is requested with a strict `json_schema` response format, so a supporting model's answer always matches the schema.
- **Token limit.** `NEXORA_LLM_MAX_TOKENS` is sent as `max_completion_tokens`. For reasoning models it covers the reasoning as well as the answer.
- **Refusals.** A model refusal, or a `content_filter` stop, falls back with `REFUSED`.
- **Retries.** The SDK retries rate limits and server errors up to `NEXORA_LLM_MAX_RETRIES` times.

## Choosing a model

Use a current model that supports structured outputs. A smaller, faster model is often enough for short goals over a handful of capabilities. Larger or reasoning models are worth it when goals are long and need many steps wired together. Watch the `INVALID_PLAN` rate in your logs to tell whether a model is good enough.

## Verified

The OpenAI client is tested against a local fake of the OpenAI API: request shape, strict schema, refusals, token-limit stops, and error mapping. The same client served real OpenAI models through [OpenRouter](openrouter), where `openai/gpt-6.1-terra` planned a five-step goal correctly. It has not yet been run against OpenAI's API directly.

## Troubleshooting

| You see | Do this |
|---------|---------|
| Plugin fails to start: "Set NEXORA_LLM_MODEL" | Name the model; OpenAI has no default |
| `REJECTED ... HTTP 401` | The key is wrong or revoked |
| `REJECTED ... HTTP 400` about `response_format` | The model does not support structured outputs; choose one that does |
| `REJECTED ... HTTP 400` about `temperature` | The model accepts only its default; unset `NEXORA_LLM_TEMPERATURE` |
| `TRUNCATED` | Raise `NEXORA_LLM_MAX_TOKENS`, especially for reasoning models |
| `UNAVAILABLE ... HTTP 429` | Rate or quota limit, after retries. Check your OpenAI usage tier |
