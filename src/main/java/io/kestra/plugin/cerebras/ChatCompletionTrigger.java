package io.kestra.plugin.cerebras;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.triggers.*;
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
    title = "Trigger a flow when a Cerebras response meets a condition",
    description = "Polls the Cerebras [Chat Completions API](https://inference-docs.cerebras.ai/api-reference/chat-completions) at each `interval`, then evaluates `stopCondition` against the current response. When it is true an execution is created; otherwise nothing happens until the next interval. The condition can use `trigger.output` (the assistant text) and `trigger.response` (the full response: `model`, `finishReason`, `usage`, ...). Every poll is a billable API call, so choose the interval accordingly. If the condition stays true, an execution is created at every interval."
)
@Plugin(
    examples = {
        @Example(
            full = true,
            title = "Trigger a flow when a Cerebras response meets a condition.",
            code = """
                id: cerebras_monitor
                namespace: company.team

                triggers:
                  - id: on_condition_met
                    type: io.kestra.plugin.cerebras.ChatCompletionTrigger
                    apiKey: "{{ secret('CEREBRAS_API_KEY') }}"
                    model: gpt-oss-120b
                    messages:
                      - role: user
                        content: "Is the system status healthy? Answer with YES or NO only."
                    interval: PT5M
                    stopCondition: "{{ trigger.output contains 'NO' }}"

                tasks:
                  - id: handle_alert
                    type: io.kestra.plugin.core.log.Log
                    message: "Alert triggered by Cerebras response: {{ trigger.output }}"
                """
        ),
        @Example(
            full = true,
            title = "Use the full response in the stop condition and in the flow.",
            code = """
                id: cerebras_usage_monitor
                namespace: company.team

                triggers:
                  - id: on_long_answer
                    type: io.kestra.plugin.cerebras.ChatCompletionTrigger
                    apiKey: "{{ secret('CEREBRAS_API_KEY') }}"
                    model: gpt-oss-120b
                    messages:
                      - role: user
                        content: "Describe the current state of the market in one paragraph."
                    maxCompletionTokens: 200
                    interval: PT1H
                    stopCondition: "{{ trigger.response.finishReason == 'length' }}"

                tasks:
                  - id: log
                    type: io.kestra.plugin.core.log.Log
                    message: "Model {{ trigger.response.model }} used {{ trigger.response.usage.totalTokens }} tokens"
                """
        )
    }
)
public class ChatCompletionTrigger extends AbstractTrigger implements PollingTriggerInterface, TriggerOutput<ChatCompletionTrigger.Output> {
    @Schema(
        title = "Polling interval",
        description = "Time between two evaluations. Each evaluation calls the Cerebras API. Defaults to 5 minutes."
    )
    @Builder.Default
    @PluginProperty(group = "execution")
    private final Duration interval = Duration.ofMinutes(5);

    @Schema(
        title = "Cerebras API key",
        description = "Sent as a Bearer token. Store it in a secret, e.g. `{{ secret('CEREBRAS_API_KEY') }}`."
    )
    @NotNull
    @PluginProperty(secret = true, group = "connection")
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Property<String> apiKey;

    @Schema(
        title = "Cerebras API base URL",
        description = "Defaults to `https://api.cerebras.ai/v1`. Override it to target a different Cerebras-compatible endpoint."
    )
    @Builder.Default
    @PluginProperty(group = "connection")
    private Property<String> baseUrl = Property.ofValue(AbstractCerebrasConnection.DEFAULT_BASE_URL);

    @Schema(
        title = "Model ID",
        description = "Cerebras model identifier, e.g. `gpt-oss-120b`."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> model;

    @Schema(
        title = "Messages",
        description = "List of messages sent at each evaluation. Each message needs a `role` and its `content`."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<List<Map<String, Object>>> messages;

    @Schema(
        title = "Stop condition",
        description = "Boolean expression evaluated against the current polling result. It can use `trigger.output` (assistant text) and `trigger.response` (full response). When true an execution is created; when false the trigger waits for the next interval."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<Boolean> stopCondition;

    @Schema(
        title = "Sampling temperature",
        description = "Not sent when unset."
    )
    @PluginProperty(group = "advanced")
    private Property<Double> temperature;

    @Schema(
        title = "Max completion tokens",
        description = "Upper bound on generated tokens, including reasoning tokens when the model uses them."
    )
    @PluginProperty(group = "advanced")
    private Property<Integer> maxCompletionTokens;

    @Override
    public Optional<Execution> evaluate(ConditionContext conditionContext, TriggerContext context) throws Exception {
        RunContext runContext = conditionContext.getRunContext();

        if (this.stopCondition == null) {
            throw new IllegalArgumentException("`stopCondition` is required, e.g. `{{ trigger.output contains 'NO' }}`.");
        }

        var task = ChatCompletion.builder()
            .id(this.getId())
            .type(ChatCompletion.class.getName())
            .apiKey(this.apiKey)
            .baseUrl(this.baseUrl)
            .model(this.model)
            .messages(this.messages)
            .temperature(this.temperature)
            .maxCompletionTokens(this.maxCompletionTokens)
            .build();

        var response = task.run(runContext);
        var text = response.getContent() == null ? "" : response.getContent();

        Map<String, Object> variables = Map.of(
            "trigger", Map.of(
                "output", text,
                "response", JacksonMapper.toMap(response)
            )
        );

        var rStopCondition = runContext.render(this.stopCondition).as(Boolean.class, variables)
            .orElseThrow(() -> new IllegalArgumentException("`stopCondition` must render to `true` or `false`, e.g. `{{ trigger.output contains 'NO' }}`."));

        if (!rStopCondition) {
            runContext.logger().debug("Stop condition not met, waiting for the next interval");
            return Optional.empty();
        }

        runContext.logger().info("Stop condition met, creating an execution");

        var output = Output.builder()
            .output(text)
            .response(response)
            .build();

        var execution = TriggerService.generateExecution(this, conditionContext, context, output);

        return Optional.of(execution);
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "Assistant text",
            description = "Content of the first choice. Empty when the model returned no text."
        )
        private final String output;

        @Schema(
            title = "Full response",
            description = "The completion as returned by Cerebras: `id`, `model`, `finishReason` and `usage`."
        )
        private final ChatCompletion.Output response;
    }
}