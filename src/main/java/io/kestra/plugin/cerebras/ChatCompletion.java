package io.kestra.plugin.cerebras;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

import com.fasterxml.jackson.core.JsonProcessingException;

import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.HttpResponse;
import io.kestra.core.http.client.HttpClientException;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Metric;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.executions.metrics.Counter;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Generate a chat completion with Cerebras",
    description = "Calls the Cerebras [Chat Completions API](https://inference-docs.cerebras.ai/api-reference/chat-completions) (`POST /chat/completions`). Optional parameters are only sent when set, so Cerebras remains the authority on what each model supports. With `stream: true` the task consumes the whole stream and returns one final output with the same shape as the non-streamed call."
)
@Plugin(
    examples = {
        @Example(
            full = true,
            title = "Generate a completion with Cerebras.",
            code = """
                id: cerebras_chat
                namespace: company.team

                inputs:
                  - id: prompt
                    type: STRING
                    defaults: "Summarize the benefits of fast AI inference in 3 bullet points."

                tasks:
                  - id: generate
                    type: io.kestra.plugin.cerebras.ChatCompletion
                    apiKey: "{{ secret('CEREBRAS_API_KEY') }}"
                    model: gpt-oss-120b
                    messages:
                      - role: user
                        content: "{{ inputs.prompt }}"
                    temperature: 0.7
                    maxCompletionTokens: 512

                  - id: log
                    type: io.kestra.plugin.core.log.Log
                    message: "{{ outputs.generate.content }}"
                """
        ),
        @Example(
            full = true,
            title = "Let the model call a function and read the requested call from the output.",
            code = """
                id: cerebras_tools
                namespace: company.team

                tasks:
                  - id: ask
                    type: io.kestra.plugin.cerebras.ChatCompletion
                    apiKey: "{{ secret('CEREBRAS_API_KEY') }}"
                    model: gpt-oss-120b
                    messages:
                      - role: user
                        content: "What is the weather in Paris?"
                    toolChoice: auto
                    tools:
                      - type: function
                        function:
                          name: get_weather
                          description: Get the current weather for a city
                          parameters:
                            type: object
                            properties:
                              city:
                                type: string
                            required:
                              - city

                  - id: log
                    type: io.kestra.plugin.core.log.Log
                    message: "{{ outputs.ask.toolCalls[0].arguments }}"
                """
        )
    },
    metrics = {
        @Metric(name = "usage.prompt.tokens", type = Counter.TYPE, unit = "tokens", description = "Number of prompt tokens."),
        @Metric(name = "usage.completion.tokens", type = Counter.TYPE, unit = "tokens", description = "Number of completion tokens."),
        @Metric(name = "usage.total.tokens", type = Counter.TYPE, unit = "tokens", description = "Total number of tokens.")
    }
)
public class ChatCompletion extends AbstractCerebrasConnection implements RunnableTask<ChatCompletion.Output> {
    @Schema(
        title = "Model ID",
        description = "Cerebras model identifier, e.g. `gpt-oss-120b`. Use the models listed in the [Cerebras docs](https://inference-docs.cerebras.ai/models/overview)."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> model;

    @Schema(
        title = "Messages",
        description = "List of messages sent as-is to the API. Each message needs a `role` (`system`, `user`, `assistant` or `tool`) and its `content`; other fields such as `tool_call_id` are forwarded unchanged."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<List<Map<String, Object>>> messages;

    @Schema(
        title = "Sampling temperature",
        description = "Higher values increase randomness. Not sent when unset."
    )
    @PluginProperty(group = "advanced")
    private Property<Double> temperature;

    @Schema(
        title = "Top-p nucleus sampling",
        description = "Not sent when unset."
    )
    @PluginProperty(group = "advanced")
    private Property<Double> topP;

    @Schema(
        title = "Max completion tokens",
        description = "Upper bound on generated tokens, including reasoning tokens when the model uses them."
    )
    @PluginProperty(group = "advanced")
    private Property<Integer> maxCompletionTokens;

    @Schema(
        title = "Seed",
        description = "Best-effort deterministic sampling."
    )
    @PluginProperty(group = "advanced")
    private Property<Integer> seed;

    @Schema(
        title = "Stop sequences",
        description = "Generation stops when one of these sequences is produced."
    )
    @PluginProperty(group = "advanced")
    private Property<List<String>> stop;

    @Schema(
        title = "Reasoning effort",
        description = "Reasoning effort for models that support it, e.g. `low`, `medium` or `high`. Accepted values depend on the model."
    )
    @PluginProperty(group = "advanced")
    private Property<String> reasoningEffort;

    @Schema(
        title = "Response format",
        description = "Forwarded as `response_format`, e.g. `{type: json_schema, json_schema: {...}}` for structured output."
    )
    @PluginProperty(group = "processing")
    private Property<Map<String, Object>> responseFormat;

    @Schema(
        title = "Tools",
        description = "Tool definitions forwarded as `tools`, in the Chat Completions format (`type: function` with a `function` object)."
    )
    @PluginProperty(group = "processing")
    private Property<List<Map<String, Object>>> tools;

    @Schema(
        title = "Tool choice",
        description = "`auto`, `none`, `required`, or the name of a function from `tools` to force that function."
    )
    @PluginProperty(group = "processing")
    private Property<String> toolChoice;

    @Schema(
        title = "Stream the response",
        description = "When true, the task consumes the full stream, concatenates the deltas and returns one final output. There is no incremental output."
    )
    @PluginProperty(group = "execution")
    private Property<Boolean> stream;

    @Override
    public Output run(RunContext runContext) throws Exception {
        var logger = runContext.logger();

        var rModel = runContext.render(this.model).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("`model` is required, e.g. `gpt-oss-120b`."));
        List<Map<String, Object>> rMessages = runContext.render(this.messages).asList(Map.class);
        if (rMessages.isEmpty()) {
            throw new IllegalArgumentException("`messages` must contain at least one message.");
        }
        var rStream = runContext.render(this.stream).as(Boolean.class).orElse(false);

        var body = new LinkedHashMap<String, Object>();
        body.put("model", rModel);
        body.put("messages", rMessages);
        runContext.render(this.temperature).as(Double.class).ifPresent(v -> body.put("temperature", v));
        runContext.render(this.topP).as(Double.class).ifPresent(v -> body.put("top_p", v));
        runContext.render(this.maxCompletionTokens).as(Integer.class).ifPresent(v -> body.put("max_completion_tokens", v));
        runContext.render(this.seed).as(Integer.class).ifPresent(v -> body.put("seed", v));
        runContext.render(this.reasoningEffort).as(String.class).ifPresent(v -> body.put("reasoning_effort", v));

        var rStop = runContext.render(this.stop).asList(String.class);
        if (!rStop.isEmpty()) {
            body.put("stop", rStop);
        }

        var rResponseFormat = runContext.render(this.responseFormat).asMap(String.class, Object.class);
        if (!rResponseFormat.isEmpty()) {
            body.put("response_format", rResponseFormat);
        }

        List<Map<String, Object>> rTools = runContext.render(this.tools).asList(Map.class);
        if (!rTools.isEmpty()) {
            body.put("tools", rTools);
        }

        runContext.render(this.toolChoice).as(String.class).ifPresent(rToolChoice ->
        {
            if (List.of("auto", "none", "required").contains(rToolChoice.toLowerCase())) {
                body.put("tool_choice", rToolChoice.toLowerCase());
            } else {
                body.put("tool_choice", Map.of("type", "function", "function", Map.of("name", rToolChoice)));
            }
        });

        if (rStream) {
            body.put("stream", true);
        }

        var request = requestBuilder(runContext, "POST", "/chat/completions")
            .body(HttpRequest.JsonRequestBody.of(body))
            .build();

        logger.debug("Sending chat completion request to Cerebras with model '{}' (stream={})", rModel, rStream);

        var output = rStream ? stream(runContext, request) : complete(runContext, request);

        if (output.getUsage() != null) {
            var usage = output.getUsage();
            if (usage.getPromptTokens() != null) {
                runContext.metric(Counter.of("usage.prompt.tokens", usage.getPromptTokens()));
            }
            if (usage.getCompletionTokens() != null) {
                runContext.metric(Counter.of("usage.completion.tokens", usage.getCompletionTokens()));
            }
            if (usage.getTotalTokens() != null) {
                runContext.metric(Counter.of("usage.total.tokens", usage.getTotalTokens()));
            }
        }

        return output;
    }

    private Output complete(RunContext runContext, HttpRequest request) throws Exception {
        var raw = send(runContext, request);

        ChatCompletionResponse response;
        try {
            response = JacksonMapper.ofJson().readValue(raw, ChatCompletionResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cerebras API returned a response that could not be parsed: " + e.getOriginalMessage(), e);
        }

        if (response.choices() == null || response.choices().isEmpty()) {
            throw new IllegalStateException("Cerebras API returned no choices — check the `model` and `messages`.");
        }

        var choice = response.choices().getFirst();
        var message = choice.message();
        var toolCalls = message == null || message.toolCalls() == null ? List.<ChatCompletionResponse.ToolCall> of() : message.toolCalls();

        return toOutput(
            response.id(),
            response.model(),
            message == null ? null : message.content(),
            choice.finishReason(),
            toolCalls.stream()
                .map(
                    tc -> Output.ToolCall.builder()
                        .id(tc.id())
                        .name(tc.function() == null ? null : tc.function().name())
                        .arguments(tc.function() == null ? null : tc.function().arguments())
                        .build()
                )
                .toList(),
            response.usage()
        );
    }

    private Output stream(RunContext runContext, HttpRequest request) throws Exception {
        var accumulator = new StreamAccumulator();

        try (var client = httpClient(runContext)) {
            client.request(request, (Consumer<HttpResponse<InputStream>>) response ->
            {
                if (response.getBody() == null) {
                    throw new IllegalStateException("Empty response from Cerebras API — retry the request, and check the Cerebras status page if the problem persists.");
                }
                try {
                    readStream(response.getBody(), accumulator);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (HttpClientException e) {
            throw translate(e);
        } catch (UncheckedIOException e) {
            throw new IllegalStateException("Failed to read the stream from the Cerebras API: " + e.getCause().getMessage(), e.getCause());
        }

        if (!accumulator.received) {
            throw new IllegalStateException("Empty stream received from Cerebras API — retry the request, and check the Cerebras status page if the problem persists.");
        }

        return accumulator.toOutput();
    }

    private static void readStream(InputStream inputStream, StreamAccumulator accumulator) throws IOException {
        try (var reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) {
                    continue;
                }

                var data = line.substring("data:".length()).trim();
                if (data.isEmpty()) {
                    continue;
                }
                if ("[DONE]".equals(data)) {
                    break;
                }

                accumulator.accept(JacksonMapper.ofJson().readValue(data, ChatCompletionResponse.class));
            }
        }
    }

    private static Output toOutput(String id, String model, String content, String finishReason, List<Output.ToolCall> toolCalls, ChatCompletionResponse.Usage usage) {
        return Output.builder()
            .id(id)
            .model(model)
            .content(content)
            .finishReason(finishReason)
            .toolCalls(toolCalls)
            .usage(
                usage == null ? null
                    : Output.Usage.builder()
                    .promptTokens(usage.promptTokens())
                    .completionTokens(usage.completionTokens())
                    .totalTokens(usage.totalTokens())
                    .build()
            )
            .build();
    }

    private static final class StreamAccumulator {
        private final StringBuilder content = new StringBuilder();
        private final Map<Integer, ToolCallParts> toolCalls = new TreeMap<>();
        private String id;
        private String model;
        private String finishReason;
        private ChatCompletionResponse.Usage usage;
        private boolean received;

        void accept(ChatCompletionResponse chunk) {
            received = true;
            if (chunk.id() != null) {
                id = chunk.id();
            }
            if (chunk.model() != null) {
                model = chunk.model();
            }
            if (chunk.usage() != null) {
                usage = chunk.usage();
            }
            if (chunk.choices() == null || chunk.choices().isEmpty()) {
                return;
            }

            var choice = chunk.choices().getFirst();
            if (choice.finishReason() != null) {
                finishReason = choice.finishReason();
            }

            var delta = choice.delta();
            if (delta == null) {
                return;
            }
            if (delta.content() != null) {
                content.append(delta.content());
            }
            if (delta.toolCalls() != null) {
                for (var toolCall : delta.toolCalls()) {
                    var parts = toolCalls.computeIfAbsent(toolCall.index() == null ? 0 : toolCall.index(), k -> new ToolCallParts());
                    if (toolCall.id() != null) {
                        parts.id = toolCall.id();
                    }
                    if (toolCall.function() != null) {
                        if (toolCall.function().name() != null) {
                            parts.name = toolCall.function().name();
                        }
                        if (toolCall.function().arguments() != null) {
                            parts.arguments.append(toolCall.function().arguments());
                        }
                    }
                }
            }
        }

        Output toOutput() {
            var calls = new ArrayList<Output.ToolCall>();
            toolCalls.values()
                .forEach(
                    p -> calls.add(
                        Output.ToolCall.builder().id(p.id).name(p.name).arguments(p.arguments.toString()).build()
                    )
                );

            return ChatCompletion.toOutput(id, model, content.isEmpty() ? null : content.toString(), finishReason, calls, usage);
        }
    }

    private static final class ToolCallParts {
        private String id;
        private String name;
        private final StringBuilder arguments = new StringBuilder();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Completion ID")
        private final String id;

        @Schema(title = "Model used")
        private final String model;

        @Schema(
            title = "Generated text",
            description = "Content of the first choice. Null when the model only returned tool calls."
        )
        private final String content;

        @Schema(
            title = "Finish reason",
            description = "Why generation stopped, e.g. `stop`, `length` or `tool_calls`."
        )
        private final String finishReason;

        @Schema(
            title = "Tool calls requested by the model",
            description = "Empty when the model did not request any tool call."
        )
        private final List<ToolCall> toolCalls;

        @Schema(title = "Token usage")
        private final Usage usage;

        @Builder
        @Getter
        public static class ToolCall {
            @Schema(title = "Tool call ID")
            private final String id;

            @Schema(title = "Function name")
            private final String name;

            @Schema(title = "Function arguments", description = "JSON-encoded arguments, as returned by the model.")
            private final String arguments;
        }

        @Builder
        @Getter
        public static class Usage {
            @Schema(title = "Prompt tokens")
            private final Long promptTokens;

            @Schema(title = "Completion tokens")
            private final Long completionTokens;

            @Schema(title = "Total tokens")
            private final Long totalTokens;
        }
    }
}