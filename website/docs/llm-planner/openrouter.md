---
id: openrouter
title: OpenRouter
sidebar_label: OpenRouter
sidebar_position: 4
---

# OpenRouter

The `openrouter` provider reaches hundreds of models from many vendors (Anthropic, OpenAI, Google, Meta, Mistral, DeepSeek, Qwen and more) through one key. OpenRouter serves the OpenAI API, so the planner uses the official OpenAI Java SDK pointed at `https://openrouter.ai/api/v1`.

## Set up

1. Create a key at [openrouter.ai](https://openrouter.ai/keys) and add credit.
2. Export it with a model name, using OpenRouter's own model id:

   ```bash
   export OPENROUTER_API_KEY=sk-or-v1-...
   export NEXORA_LLM_MODEL=meta-llama/llama-4-maverick
   ```

OpenRouter model ids are `vendor/model`, and they write versions with dots: Claude Opus 5.5 is `anthropic/claude-opus-5.5`, not `claude-opus-5-5`. List them, with the parameters each supports:

```bash
curl -s https://openrouter.ai/api/v1/models | jq -r '.data[] | select(.supported_parameters | index("structured_outputs")) | .id'
```

Choose a model whose `supported_parameters` include `structured_outputs`.

## Settings

| Variable | Default | Notes |
|----------|---------|-------|
| `OPENROUTER_API_KEY` | | Or `NEXORA_LLM_API_KEY_FILE` |
| `NEXORA_LLM_MODEL` | required | An OpenRouter model id |
| `NEXORA_LLM_MAX_TOKENS` | `16000` | See [credit](#credit-and-max_tokens) below; often worth lowering |
| `NEXORA_LLM_TEMPERATURE` | the model's default | `0` to `2` |
| `NEXORA_LLM_EFFORT` | | Not accepted; the plugin refuses to start with it set |

The [overview](overview#settings-shared-by-every-provider) lists the settings every provider shares.

## How it behaves

- **Structured output.** The plan is requested with a strict `json_schema` response format, which OpenRouter passes to the model. Models that ignore it are still caught by the plan checks.
- **Token limit.** Sent as `max_tokens`.
- **Routing.** OpenRouter picks which upstream host serves the model. Latency varies by model and host; in testing it ranged from 7 to 19 seconds for the same five-step goal.

## Credit and `max_tokens`

OpenRouter reserves credit for the full `max_tokens` before it answers. On a key with little credit, the default `16000` can be refused outright with HTTP `402`, which the planner reports as `REJECTED`:

```
reason=REJECTED detail=OpenRouter API returned HTTP 402: This request requires more credits, or fewer max_tokens.
You requested up to 16000 tokens, but can only afford 2000.
```

A plan answer itself needs only a few hundred tokens, so lowering `NEXORA_LLM_MAX_TOKENS` fixes it. Thinking models, Gemini Flash for example, also spend part of the limit on reasoning: give them a few thousand tokens, or the answer comes back `TRUNCATED`.

## When to use OpenRouter

- **Comparing models.** Change `NEXORA_LLM_MODEL` and rerun to compare plan quality, latency and cost across vendors, with one key and no code change.
- **Vendors without a Java SDK.** Meta Llama, Mistral, DeepSeek, Qwen and others.
- **One bill.** Many models behind one account and one key.

For production traffic to a single vendor, the native provider ([Anthropic](anthropic), [OpenAI](openai), [Gemini](gemini)) removes a hop and lets you use that vendor's own features.

## Verified

Tested against the real OpenRouter API with a five-step goal ("validate and fraud-check the order, reserve stock, charge it, email a receipt that references the charge"). All four models planned every step correctly, with the receipt's `chargeId` bound to the charge step's output:

| Model | Result | Time |
|-------|--------|------|
| `meta-llama/llama-4-maverick` | All 5 steps | 19 s |
| `openai/gpt-6.1-terra` | All 5 steps | 7 s |
| `google/gemini-3.8-flash` | All 5 steps, at `max_tokens` 9000 | 13 s |
| `anthropic/claude-opus-5.5` | All 5 steps, at `max_tokens` 1700 | 12 s |

The low-credit `402`, the `TRUNCATED` answer and an invalid model id all fell back to the rule-based plan as expected.

## Troubleshooting

| You see | Do this |
|---------|---------|
| `REJECTED ... HTTP 402` "requires more credits" | Lower `NEXORA_LLM_MAX_TOKENS`, or add credit |
| `REJECTED ... HTTP 400` "is not a valid model ID" | Use the exact id from the models list, with dots, for example `anthropic/claude-opus-5.5` |
| `TRUNCATED` | Raise `NEXORA_LLM_MAX_TOKENS`; thinking models need a few thousand |
| `INVALID_PLAN` often with one model | That model follows the schema poorly; choose one with `structured_outputs` |
