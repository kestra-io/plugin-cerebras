package io.kestra.plugin.cerebras;

import java.util.List;
import java.util.Map;

import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.Test;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.utils.IdUtils;


import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
class ChatCompletionTest extends AbstractCerebrasTest {
    private static final String PATH = "/v1/chat/completions";

    private ChatCompletion.ChatCompletionBuilder<?, ?> task() {
        return ChatCompletion.builder()
            .id(IdUtils.create())
            .type(ChatCompletion.class.getName())
            .apiKey(Property.ofValue(API_KEY))
            .baseUrl(Property.ofValue(wireMock.baseUrl() + "/v1"))
            .model(Property.ofValue("gpt-oss-120b"))
            .messages(Property.ofValue(List.of(Map.of("role", "user", "content", "Hello"))));
    }

    private void stub(String body) {
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(okJson(body)));
    }

    @Test
    void shouldReturnCompletionAndRenderProperties() throws Exception {
        stub("""
            {
              "id": "chatcmpl-1",
              "object": "chat.completion",
              "model": "gpt-oss-120b",
              "unknown_provider_field": {"a": 1},
              "choices": [{"index": 0, "finish_reason": "stop", "message": {"role": "assistant", "content": "Hi there"}}],
              "usage": {"prompt_tokens": 5, "completion_tokens": 3, "total_tokens": 8}
            }
            """);

        var runContext = runContextFactory.of(Map.of("modelName", "gpt-oss-120b", "key", API_KEY));
        var output = task()
            .apiKey(Property.ofExpression("{{ key }}"))
            .model(Property.ofExpression("{{ modelName }}"))
            .build()
            .run(runContext);

        assertThat(output.getId(), is("chatcmpl-1"));
        assertThat(output.getModel(), is("gpt-oss-120b"));
        assertThat(output.getContent(), is("Hi there"));
        assertThat(output.getFinishReason(), is("stop"));
        assertThat(output.getToolCalls(), empty());
        assertThat(output.getUsage().getTotalTokens(), is(8L));
        assertThat(
            runContext.metrics().stream().map(m -> m.getName()).toList(),
            hasItems(
                "usage.prompt.tokens",
                "usage.completion.tokens",
                "usage.total.tokens"
            )
        );


        var request = postRequestedFor(urlEqualTo(PATH))
            .withHeader("Authorization", equalTo("Bearer " + API_KEY))
            .withRequestBody(matchingJsonPath("$.model", equalTo("gpt-oss-120b")))
            .withRequestBody(matchingJsonPath("$.messages[0].role", equalTo("user")))
            .withRequestBody(matchingJsonPath("$.messages[0].content", equalTo("Hello")));


        for (var field : List.of("temperature", "top_p", "max_completion_tokens", "seed", "stop", "reasoning_effort", "response_format", "tools", "tool_choice", "stream")) {
            request.withRequestBody(WireMock.not(matchingJsonPath("$." + field)));
        }

        wireMock.verify(request);
    }

    @Test
    void shouldSendOptionalParametersAndMapToolCalls() throws Exception {
        stub("""
            {
              "id": "chatcmpl-2",
              "model": "gpt-oss-120b",
              "choices": [{"index": 0, "finish_reason": "tool_calls", "message": {"role": "assistant", "content": null,
                "tool_calls": [{"id": "call_1", "type": "function", "function": {"name": "get_weather", "arguments": "{\\"city\\":\\"Paris\\"}"}}]}}]
            }
            """);

        var tool = Map.<String, Object> of(
            "type", "function",
            "function", Map.of("name", "get_weather", "parameters", Map.of("type", "object"))
        );

        var output = task()
            .temperature(Property.ofValue(0.2))
            .topP(Property.ofValue(0.9))
            .maxCompletionTokens(Property.ofValue(128))
            .seed(Property.ofValue(42))
            .stop(Property.ofValue(List.of("END")))
            .reasoningEffort(Property.ofValue("low"))
            .responseFormat(Property.ofValue(Map.of("type", "json_object")))
            .tools(Property.ofValue(List.of(tool)))
            .toolChoice(Property.ofValue("get_weather"))
            .build()
            .run(runContextFactory.of());

        assertThat(output.getContent(), nullValue());
        assertThat(output.getFinishReason(), is("tool_calls"));
        assertThat(output.getToolCalls(), hasSize(1));
        assertThat(output.getToolCalls().getFirst().getName(), is("get_weather"));
        assertThat(output.getToolCalls().getFirst().getArguments(), containsString("Paris"));

        wireMock.verify(
            postRequestedFor(urlEqualTo(PATH))
                .withRequestBody(matchingJsonPath("$.temperature", equalTo("0.2")))
                .withRequestBody(matchingJsonPath("$.top_p", equalTo("0.9")))
                .withRequestBody(matchingJsonPath("$.max_completion_tokens", equalTo("128")))
                .withRequestBody(matchingJsonPath("$.seed", equalTo("42")))
                .withRequestBody(matchingJsonPath("$.stop[0]", equalTo("END")))
                .withRequestBody(matchingJsonPath("$.reasoning_effort", equalTo("low")))
                .withRequestBody(matchingJsonPath("$.response_format.type", equalTo("json_object")))
                .withRequestBody(matchingJsonPath("$.tools[0].function.name", equalTo("get_weather")))
                .withRequestBody(matchingJsonPath("$.tool_choice.function.name", equalTo("get_weather")))
        );
    }

    @Test
    void shouldSendKeywordToolChoiceAsString() throws Exception {
        stub("""
            {"id": "c", "model": "m", "choices": [{"index": 0, "finish_reason": "stop", "message": {"role": "assistant", "content": "ok"}}]}
            """);

        task().toolChoice(Property.ofValue("required")).build().run(runContextFactory.of());

        wireMock.verify(postRequestedFor(urlEqualTo(PATH)).withRequestBody(matchingJsonPath("$.tool_choice", equalTo("required"))));
    }

    @Test
    void shouldAggregateStreamedContent() throws Exception {
        wireMock.stubFor(
            post(urlEqualTo(PATH)).willReturn(
                ok().withHeader("Content-Type", "text/event-stream")
                    .withBody("""
                        data: {"id":"chatcmpl-3","model":"gpt-oss-120b","choices":[{"index":0,"delta":{"role":"assistant"}}]}

                        data: {"choices":[{"index":0,"delta":{"content":"Hello"}}]}

                        data: {"choices":[{"index":0,"delta":{"content":" world"},"finish_reason":"stop"}],"usage":{"prompt_tokens":4,"completion_tokens":2,"total_tokens":6}}

                        data: [DONE]

                        """)
            )
        );

        var output = task().stream(Property.ofValue(true)).build().run(runContextFactory.of());

        assertThat(output.getId(), is("chatcmpl-3"));
        assertThat(output.getModel(), is("gpt-oss-120b"));
        assertThat(output.getContent(), is("Hello world"));
        assertThat(output.getFinishReason(), is("stop"));
        assertThat(output.getToolCalls(), empty());
        assertThat(output.getUsage().getTotalTokens(), is(6L));

        wireMock.verify(postRequestedFor(urlEqualTo(PATH)).withRequestBody(matchingJsonPath("$.stream", equalTo("true"))));
    }

    @Test
    void shouldAggregateStreamedToolCalls() throws Exception {
        wireMock.stubFor(
            post(urlEqualTo(PATH)).willReturn(
                ok().withHeader("Content-Type", "text/event-stream")
                    .withBody("""
                        data: {"id":"c4","model":"m","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"get_weather","arguments":"{\\"city\\""}}]}}]}

                        data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":":\\"Paris\\"}"}}]},"finish_reason":"tool_calls"}]}

                        data: [DONE]

                        """)
            )
        );

        var output = task().stream(Property.ofValue(true)).build().run(runContextFactory.of());

        assertThat(output.getContent(), nullValue());
        assertThat(output.getFinishReason(), is("tool_calls"));
        assertThat(output.getToolCalls(), hasSize(1));
        assertThat(output.getToolCalls().getFirst().getId(), is("call_1"));
        assertThat(output.getToolCalls().getFirst().getArguments(), is("{\"city\":\"Paris\"}"));
    }

    @Test
    void shouldFailWithActionableMessageOnUnauthorized() {
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(401).withBody("{\"message\":\"Wrong API Key\"}")));

        var exception = assertThrows(IllegalStateException.class, () -> task().build().run(runContextFactory.of()));

        assertThat(exception.getMessage(), containsString("HTTP 401"));
        assertThat(exception.getMessage(), containsString("API key"));
        assertThat(exception.getMessage(), not(containsString(API_KEY)));
    }

    @Test
    void shouldFailOnStreamedUnauthorized() {
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(401).withBody("{\"message\":\"Wrong API Key\"}")));

        var exception = assertThrows(
            IllegalStateException.class,
            () -> task().stream(Property.ofValue(true)).build().run(runContextFactory.of())
        );

        assertThat(exception.getMessage(), containsString("HTTP 401"));
    }

    @Test
    void shouldFailOnEmptyBody() {
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(ok()));

        var exception = assertThrows(IllegalStateException.class, () -> task().build().run(runContextFactory.of()));

        assertThat(exception.getMessage(), containsString("Empty response"));
    }

    @Test
    void shouldFailWhenNoChoicesAreReturned() {
        stub("{\"id\": \"c\", \"model\": \"m\", \"choices\": []}");

        var exception = assertThrows(IllegalStateException.class, () -> task().build().run(runContextFactory.of()));

        assertThat(exception.getMessage(), containsString("no choices"));
    }

    @Test
    void shouldNotExposeApiKeyInToString() {
        assertThat(task().build().toString(), not(containsString(API_KEY)));
    }

    @Test
    void shouldFailOnMalformedJsonResponse() {
        stub("{ invalid json");

        var exception = assertThrows(IllegalStateException.class, () -> task().build().run(runContextFactory.of()));

        assertThat(exception.getMessage(), containsString("could not be parsed"));
    }

    @Test
    void shouldFailOnEmptyStream() {
        wireMock.stubFor(
            post(urlEqualTo(PATH)).willReturn(
                ok().withHeader("Content-Type", "text/event-stream")
                    .withBody("data: [DONE]\n\n")
            )
        );

        var exception = assertThrows(
            IllegalStateException.class,
            () -> task().stream(Property.ofValue(true)).build().run(runContextFactory.of())
        );

        assertThat(exception.getMessage(), containsString("Empty stream"));
    }
    @Test
    void shouldRenderExpressionsInMessages() throws Exception {
        stub("""
        {"id": "c", "model": "m", "choices": [{"index": 0, "finish_reason": "stop", "message": {"role": "assistant", "content": "ok"}}]}
        """);

        var runContext = runContextFactory.of(Map.of("prompt", "Hi from flow"));
        task()
            .messages(Property.ofExpression("[{\"role\": \"user\", \"content\": \"{{ prompt }}\"}]"))
            .build()
            .run(runContext);

        wireMock.verify(postRequestedFor(urlEqualTo(PATH)).withRequestBody(matchingJsonPath("$.messages[0].content", equalTo("Hi from flow"))));
    }
}