package io.kestra.plugin.cerebras;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.utils.IdUtils;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
class ListModelsTest extends AbstractCerebrasTest {
    private static final String PATH = "/v1/models";

    private ListModels.ListModelsBuilder<?, ?> task() {
        return ListModels.builder()
            .id(IdUtils.create())
            .type(ListModels.class.getName())
            .apiKey(Property.ofValue(API_KEY))
            .baseUrl(Property.ofValue(wireMock.baseUrl() + "/v1"));
    }

    private void stub(String body) {
        wireMock.stubFor(get(urlEqualTo(PATH)).willReturn(okJson(body)));
    }

    @Test
    void shouldListModelsAndRenderProperties() throws Exception {
        stub("""
            {
              "object": "list",
              "data": [
                {"id": "gpt-oss-120b", "object": "model", "created": 1700000000, "owned_by": "Cerebras", "unknown_provider_field": {"a": 1}},
                {"id": "llama3.1-8b", "object": "model", "created": 0, "owned_by": "Cerebras"}
              ]
            }
            """);

        var runContext = runContextFactory.of(
            Map.of(
                "key", API_KEY,
                "baseUrl", wireMock.baseUrl() + "/v1"
            )
        );

        var output = task()
            .apiKey(Property.ofExpression("{{ key }}"))
            .baseUrl(Property.ofExpression("{{ baseUrl }}"))
            .build()
            .run(runContext);

        assertThat(output.getModels(), hasSize(2));
        assertThat(output.getModels().getFirst().getId(), is("gpt-oss-120b"));
        assertThat(output.getModels().getFirst().getOwnedBy(), is("Cerebras"));
        assertThat(output.getModels().getFirst().getCreated(), is(Instant.ofEpochSecond(1700000000L)));
        assertThat(output.getModels().get(1).getId(), is("llama3.1-8b"));
        assertThat(output.getModels().get(1).getCreated(), nullValue());

        wireMock.verify(
            1,
            getRequestedFor(urlEqualTo(PATH))
                .withHeader("Authorization", equalTo("Bearer " + API_KEY))
        );
    }

    @Test
    void shouldReturnEmptyListWhenNoModelIsAvailable() throws Exception {
        stub("{\"object\": \"list\", \"data\": []}");

        var output = task().build().run(runContextFactory.of());

        assertThat(output.getModels(), empty());
    }

    @Test
    void shouldTolerateTrailingSlashInBaseUrl() throws Exception {
        stub("{\"data\": [{\"id\": \"m\"}]}");

        var output = task().baseUrl(Property.ofValue(wireMock.baseUrl() + "/v1/")).build().run(runContextFactory.of());

        assertThat(output.getModels(), hasSize(1));
        assertThat(output.getModels().getFirst().getOwnedBy(), nullValue());
    }

    @Test
    void shouldFailWithActionableMessageOnUnauthorized() {
        wireMock.stubFor(get(urlEqualTo(PATH)).willReturn(aResponse().withStatus(401).withBody("{\"message\":\"Wrong API Key\"}")));

        var exception = assertThrows(IllegalStateException.class, () -> task().build().run(runContextFactory.of()));

        assertThat(exception.getMessage(), containsString("HTTP 401"));
        assertThat(exception.getMessage(), containsString("API key"));
        assertThat(exception.getMessage(), not(containsString(API_KEY)));
    }

    @Test
    void shouldFailOnEmptyBody() {
        wireMock.stubFor(get(urlEqualTo(PATH)).willReturn(ok()));

        var exception = assertThrows(IllegalStateException.class, () -> task().build().run(runContextFactory.of()));

        assertThat(exception.getMessage(), containsString("Empty response"));
    }

    @Test
    void shouldFailWhenModelListIsMissing() {
        stub("{\"object\": \"list\"}");

        var exception = assertThrows(IllegalStateException.class, () -> task().build().run(runContextFactory.of()));

        assertThat(exception.getMessage(), containsString("no model list"));
    }

    @Test
    void shouldFailWhenApiKeyIsBlank() {
        var exception = assertThrows(
            IllegalArgumentException.class,
            () -> task().apiKey(Property.ofValue(" ")).build().run(runContextFactory.of())
        );

        assertThat(exception.getMessage(), containsString("apiKey"));
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
}