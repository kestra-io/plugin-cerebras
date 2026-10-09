package io.kestra.plugin.cerebras;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatCompletionResponse(String id, String model, List<Choice> choices, Usage usage) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Choice(Integer index, Message message, Message delta, @JsonProperty("finish_reason") String finishReason) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Message(String role, String content, @JsonProperty("tool_calls") List<ToolCall> toolCalls) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ToolCall(Integer index, String id, String type, Function function) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Function(String name, String arguments) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Usage(
        @JsonProperty("prompt_tokens") Long promptTokens,
        @JsonProperty("completion_tokens") Long completionTokens,
        @JsonProperty("total_tokens") Long totalTokens) {
    }
}