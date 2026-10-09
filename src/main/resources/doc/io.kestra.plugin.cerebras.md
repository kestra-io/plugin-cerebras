# How to use the Cerebras plugin

Call Cerebras models for chat completions, list available models, and trigger flows from model responses in Kestra flows.

## Authentication

Set `apiKey` to your Cerebras API key. Store it in a [secret](https://kestra.io/docs/concepts/secret). Optionally override `baseUrl` to point at a different Cerebras-compatible endpoint; it defaults to `https://api.cerebras.ai/v1`.

## Tasks

`ChatCompletion` sends a list of messages to a model and returns the response — use it for text generation, summarization, classification, structured output, or tool calls. Set `stream: true` to consume a streamed response; the task still returns a single final output. `ListModels` returns the models available to your API key, so you can pick a valid `model` for `ChatCompletion`.

## Triggers

`ChatCompletionTrigger` polls Cerebras at each `interval` with a prompt and evaluates `stopCondition` against the current response. When the condition is true, it creates an execution; otherwise it waits for the next interval. Use `trigger.output` for the assistant text and `trigger.response` for the full response (`model`, `finishReason`, `usage`). Each poll is a billable API call, so choose the interval accordingly.