---
id: gemini
title: Google Gemini
sidebar_label: Gemini
sidebar_position: 5
---

# Google Gemini

The `gemini` provider plans with Google Gemini models on the Gemini Developer API, through Google's official Gen AI Java SDK.

## Set up

1. Create an API key in [Google AI Studio](https://aistudio.google.com/apikey). The key belongs to a Google Cloud project, which must be active.
2. Export it with a model name. Gemini model names change often, so there is no default:

   ```bash
   export GEMINI_API_KEY=...
   export NEXORA_LLM_MODEL="your-gemini-model-id"   # replace with a model your project can use
   ```

List the models your key can use:

```bash
curl -s "https://generativelanguage.googleapis.com/v1beta/models" -H "x-goog-api-key: $GEMINI_API_KEY" \
  | jq -r '.models[] | select(.supportedGenerationMethods | index("generateContent")) | .name'
```

Use the name without the `models/` prefix. With only `GEMINI_API_KEY` set, the planner selects Gemini; with several keys set, add `NEXORA_LLM_PROVIDER=gemini`.

## Settings

| Variable | Default | Notes |
|----------|---------|-------|
| `GEMINI_API_KEY` | | Or `NEXORA_LLM_API_KEY_FILE` |
| `NEXORA_LLM_MODEL` | required | A Gemini model that supports `generateContent` |
| `NEXORA_LLM_MAX_TOKENS` | `16000` | Sent as `maxOutputTokens`; includes thinking on thinking models |
| `NEXORA_LLM_TEMPERATURE` | the model's default | `0` to `2` |
| `NEXORA_LLM_EFFORT` | | Not accepted; the plugin refuses to start with it set |
| `NEXORA_LLM_BASE_URL` | Google's API | For a proxy or gateway |

The [overview](overview#settings-shared-by-every-provider) lists the settings every provider shares.

## How it behaves

- **Structured output.** The plan is requested as `application/json` constrained by the plan schema (`responseJsonSchema`).
- **Safety.** A blocked prompt, or an answer stopped by a safety filter (`SAFETY`, `PROHIBITED_CONTENT`, `BLOCKLIST`, `SPII`, `RECITATION`), falls back with `REFUSED`.
- **Token limit.** An answer stopped at `MAX_TOKENS` falls back with `TRUNCATED`. Thinking models spend part of the limit on reasoning.
- **Retries.** The SDK retries transient failures up to `NEXORA_LLM_MAX_RETRIES` times.

## Gemini directly or through OpenRouter

Gemini is also reachable through [OpenRouter](openrouter) (for example `google/gemini-3.8-flash`). Call Google directly when you already have a Google Cloud project and billing, want requests to go only to Google, or want Google's own quotas and data terms. Use OpenRouter to compare Gemini against other vendors behind one key.

## Verified

- The Gemini client is tested against a local fake of the Gemini API: request shape, JSON schema, safety blocks, token-limit stops, and error mapping.
- Against the real API, a request reached Google through the SDK, and Google's `403 PERMISSION_DENIED` fell back to the rule-based plan with `REJECTED` and Google's message.
- A real Gemini model (`google/gemini-3.8-flash`) planned a five-step goal correctly through OpenRouter.

**Not yet verified:** a successful plan on the native Gemini API. In particular, whether Gemini accepts every part of the plan schema (`anyOf` for nullable fields and `additionalProperties: false`). If it rejects them, calls fail with `REJECTED` and the engine plans with its rules, so nothing breaks, but Gemini would not plan. Report the result if you run it.

## Troubleshooting

| You see | Do this |
|---------|---------|
| `REJECTED ... HTTP 403 PERMISSION_DENIED` "has been suspended" | The key's Google Cloud project is suspended. Reactivate it in the Cloud console, or create a key in a new project |
| `REJECTED ... HTTP 403` other | The Generative Language API is not enabled for the project, or the key is restricted |
| `REJECTED ... HTTP 400 INVALID_ARGUMENT` about the schema | Gemini rejected part of the plan schema; please report it |
| `REJECTED ... HTTP 404` | The model name is wrong; list models and drop the `models/` prefix |
| `REFUSED` | A safety filter blocked the goal or answer; reword the goal or capability descriptions |
| `TRUNCATED` | Raise `NEXORA_LLM_MAX_TOKENS` |
