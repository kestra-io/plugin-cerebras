package io.kestra.plugin.cerebras;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.Label;
import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.validations.ModelValidator;
import io.kestra.core.utils.IdUtils;
import io.kestra.core.utils.TestsUtils;

import jakarta.inject.Inject;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
class ChatCompletionTriggerTest extends AbstractCerebrasTest {
    private static final String PATH = "/v1/chat/completions";

    @Inject
    private ModelValidator modelValidator;

    private ChatCompletionTrigger.ChatCompletionTriggerBuilder<?, ?> trigger() {
        return ChatCompletionTrigger.builder()
            .id(IdUtils.create())
            .type(ChatCompletionTrigger.class.getName())
            .apiKey(Property.ofValue(API_KEY))
            .baseUrl(Property.ofValue(wireMock.baseUrl() + "/v1"))
            .model(Property.ofValue("gpt-oss-120b"))
            .messages(Property.ofValue(List.of(Map.of("role", "user", "content", "Is the system status healthy? Answer with YES or NO only."))))
            .stopCondition(Property.ofValue(true));
    }

    private void stubCompletion(String content) {
        wireMock.stubFor(
            post(urlEqualTo(PATH)).willReturn(
                okJson("""
                    {
                      "id": "chatcmpl-1",
                      "object": "chat.completion",
                      "model": "gpt-oss-120b",
                      "unknown_provider_field": {"a": 1},
                      "choices": [{"index": 0, "finish_reason": "stop", "message": {"role": "assistant", "content": "%s"}}],
                      "usage": {"prompt_tokens": 5, "completion_tokens": 3, "total_tokens": 8}
                    }
                    """.formatted(content))
            )
        );
    }

    private Optional<Execution> evaluate(ChatCompletionTrigger trigger) throws Exception {
        Map.Entry<ConditionContext, io.kestra.core.models.triggers.Trigger> mocked = TestsUtils.mockTrigger(runContextFactory, trigger);

        return trigger.evaluate(mocked.getKey(), mocked.getValue());
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldCreateExecutionWithOutputAndResponseWhenStopConditionIsTrue() throws Exception {
        stubCompletion("NO");

        var execution = evaluate(
            trigger().stopCondition(Property.ofExpression("{{ trigger.output == 'NO' }}")).build()
        );

        assertThat(execution.isPresent(), is(true));

        var variables = execution.get().getTrigger().getVariables();
        assertThat(variables.get("output"), is("NO"));

        var response = (Map<String, Object>) variables.get("response");
        assertThat(response.get("id"), is("chatcmpl-1"));
        assertThat(response.get("model"), is("gpt-oss-120b"));
        assertThat(response.get("finishReason"), is("stop"));
        var usage = (Map<String, Object>) response.get("usage");
        assertThat(((Number) usage.get("totalTokens")).longValue(), is(8L));

        wireMock.verify(
            1,
            postRequestedFor(urlEqualTo(PATH))
                .withHeader("Authorization", equalTo("Bearer " + API_KEY))
                .withRequestBody(matchingJsonPath("$.model", equalTo("gpt-oss-120b")))
                .withRequestBody(matchingJsonPath("$.messages[0].role", equalTo("user")))
        );
    }

    @Test
    void shouldKeepTriggerLabelsAndUseTriggerExecutionId() throws Exception {
        stubCompletion("NO");

        var trigger = trigger()
            .labels(List.of(new Label("team", "ai")))
            .stopCondition(Property.ofExpression("{{ trigger.output == 'NO' }}"))
            .build();

        var mocked = TestsUtils.mockTrigger(runContextFactory, trigger);
        var execution = trigger.evaluate(mocked.getKey(), mocked.getValue());

        assertThat(execution.isPresent(), is(true));
        assertThat(
            execution.get().getLabels(),
            hasItems(new Label("team", "ai"), new Label(Label.FROM, "trigger"))
        );
        assertThat(
            execution.get().getId(),
            is(mocked.getKey().getRunContext().getTriggerExecutionId())
        );
    }

    @Test
    void shouldNotCreateExecutionWhenStopConditionIsFalse() throws Exception {
        stubCompletion("YES");

        var execution = evaluate(
            trigger().stopCondition(Property.ofExpression("{{ trigger.output == 'NO' }}")).build()
        );

        assertThat(execution.isPresent(), is(false));
        wireMock.verify(1, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void shouldEvaluateIssueExampleCondition() throws Exception {
        stubCompletion("NO");

        var matching = evaluate(
            trigger().stopCondition(Property.ofExpression("{{ trigger.output contains 'NO' }}")).build()
        );
        assertThat(matching.isPresent(), is(true));

        stubCompletion("YES");

        var notMatching = evaluate(
            trigger().stopCondition(Property.ofExpression("{{ trigger.output contains 'NO' }}")).build()
        );
        assertThat(notMatching.isPresent(), is(false));
    }

    @Test
    void shouldEvaluateStopConditionAgainstFullResponse() throws Exception {
        stubCompletion("whatever");

        var fired = evaluate(
            trigger().stopCondition(Property.ofExpression("{{ trigger.response.finishReason == 'stop' and trigger.response.usage.totalTokens == 8 }}")).build()
        );
        assertThat(fired.isPresent(), is(true));

        var skipped = evaluate(
            trigger().stopCondition(Property.ofExpression("{{ trigger.response.finishReason == 'length' }}")).build()
        );
        assertThat(skipped.isPresent(), is(false));
    }

    @Test
    void shouldRenderExpressionsInAllProperties() throws Exception {
        stubCompletion("NO");

        var trigger = trigger()
            .apiKey(Property.ofExpression("{{ 'test-secret' ~ '-key' }}"))
            .baseUrl(Property.ofExpression("{{ '" + wireMock.baseUrl() + "' ~ '/v1' }}"))
            .model(Property.ofExpression("{{ 'gpt-oss' ~ '-120b' }}"))
            .messages(Property.ofExpression("[{\"role\": \"user\", \"content\": \"Namespace: {{ flow.namespace }}\"}]"))
            .temperature(Property.ofExpression("{{ 0.2 }}"))
            .maxCompletionTokens(Property.ofExpression("{{ 100 }}"))
            .stopCondition(Property.ofExpression("{{ trigger.output == 'NO' }}"))
            .build();

        var mocked = TestsUtils.mockTrigger(runContextFactory, trigger);
        var expectedNamespace = mocked.getKey().getFlow().getNamespace();

        var execution = trigger.evaluate(mocked.getKey(), mocked.getValue());

        assertThat(execution.isPresent(), is(true));

        wireMock.verify(
            1,
            postRequestedFor(urlEqualTo(PATH))
                .withHeader("Authorization", equalTo("Bearer " + API_KEY))
                .withRequestBody(matchingJsonPath("$.model", equalTo("gpt-oss-120b")))
                .withRequestBody(matchingJsonPath("$.messages[0].content", equalTo("Namespace: " + expectedNamespace)))
                .withRequestBody(matchingJsonPath("$.temperature", equalTo("0.2")))
                .withRequestBody(matchingJsonPath("$.max_completion_tokens", equalTo("100")))
        );
    }

    @Test
    void shouldExposeEmptyOutputWhenModelReturnsNoText() throws Exception {
        wireMock.stubFor(
            post(urlEqualTo(PATH)).willReturn(
                okJson("""
                    {"id": "c", "model": "m", "choices": [{"index": 0, "finish_reason": "length", "message": {"role": "assistant", "content": null}}]}
                    """)
            )
        );

        var execution = evaluate(
            trigger().stopCondition(Property.ofExpression("{{ trigger.output == '' }}")).build()
        );

        assertThat(execution.isPresent(), is(true));
        assertThat(execution.get().getTrigger().getVariables().get("output"), is(""));
    }

    @Test
    void shouldPropagateApiFailure() {
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(401).withBody("{\"message\":\"Wrong API Key\"}")));

        var exception = assertThrows(IllegalStateException.class, () -> evaluate(trigger().build()));

        assertThat(exception.getMessage(), containsString("HTTP 401"));
        assertThat(exception.getMessage(), containsString("API key"));
        assertThat(exception.getMessage(), not(containsString(API_KEY)));
    }

    @Test
    void shouldFailBeforeCallingApiWhenStopConditionIsMissing() {
        var exception = assertThrows(IllegalArgumentException.class, () -> evaluate(trigger().stopCondition(null).build()));

        assertThat(exception.getMessage(), containsString("`stopCondition` is required"));
        wireMock.verify(0, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void shouldFailWhenStopConditionRendersToNothing() {
        stubCompletion("NO");

        var exception = assertThrows(
            IllegalArgumentException.class,
            () -> evaluate(trigger().stopCondition(Property.ofExpression("{{ null }}")).build())
        );

        assertThat(exception.getMessage(), containsString("`stopCondition` must render"));
    }

    @Test
    void shouldRejectMissingStopConditionAtValidation() {
        var violations = modelValidator.isValid(trigger().stopCondition(null).build());

        assertThat(violations.isPresent(), is(true));
        assertThat(violations.get().getMessage(), containsString("stopCondition"));
    }

    @Test
    void shouldUseFiveMinutesAsDefaultInterval() {
        assertThat(trigger().build().getInterval(), is(Duration.ofMinutes(5)));
    }

    @Test
    void shouldNotExposeApiKeyInToString() {
        assertThat(trigger().build().toString(), not(containsString(API_KEY)));
    }
}