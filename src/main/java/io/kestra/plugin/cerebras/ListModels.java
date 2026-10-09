package io.kestra.plugin.cerebras;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;

import io.swagger.v3.oas.annotations.media.Schema;
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
    title = "List available Cerebras models",
    description = "Calls the Cerebras [Models API](https://inference-docs.cerebras.ai/api-reference/models) (`GET /models`) and returns the models available to your API key."
)
@Plugin(
    examples = {
        @Example(
            full = true,
            title = "List available Cerebras models and log them.",
            code = """
                id: list_cerebras_models
                namespace: company.team

                tasks:
                  - id: list_models
                    type: io.kestra.plugin.cerebras.ListModels
                    apiKey: "{{ secret('CEREBRAS_API_KEY') }}"

                  - id: log_models
                    type: io.kestra.plugin.core.log.Log
                    message: "Available models: {{ outputs.list_models.models }}"
                """
        )
    }
)
public class ListModels extends AbstractCerebrasConnection implements RunnableTask<ListModels.Output> {

    @Override
    public Output run(RunContext runContext) throws Exception {
        var request = requestBuilder(runContext, "GET", "/models").build();

        runContext.logger().debug("Listing Cerebras models");

        var raw = send(runContext, request);

        ModelsResponse response;
        try {
            response = JacksonMapper.ofJson().readValue(raw, ModelsResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cerebras API returned a response that could not be parsed: " + e.getOriginalMessage(), e);
        }

        if (response.data() == null) {
            throw new IllegalStateException("Cerebras API returned no model list — check `baseUrl` and the API key permissions.");
        }

        var models = response.data()
            .stream()
            .map(
                entry -> Output.Model.builder()
                    .id(entry.id())
                    .ownedBy(entry.ownedBy())
                    .created(entry.created() == null || entry.created() <= 0 ? null : Instant.ofEpochSecond(entry.created()))
                    .build()
            )
            .toList();

        return Output.builder().models(models).build();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ModelsResponse(List<ModelEntry> data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ModelEntry(String id, Long created, @JsonProperty("owned_by") String ownedBy) {
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "Available models",
            description = "Models returned by the API, in the order received. Empty when no model is available."
        )
        private final List<Model> models;

        @Builder
        @Getter
        public static class Model {
            @Schema(title = "Model ID", description = "Identifier to use as `model` in `ChatCompletion`.")
            private final String id;

            @Schema(title = "Model owner")
            private final String ownedBy;

            @Schema(
                title = "Creation date",
                description = "Model creation time. Null when the API does not provide a meaningful timestamp."
            )
            private final Instant created;
        }
    }
}