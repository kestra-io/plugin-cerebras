package io.kestra.plugin.cerebras;

import java.net.URI;
import java.time.Duration;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.client.HttpClient;
import io.kestra.core.http.client.HttpClientException;
import io.kestra.core.http.client.HttpClientResponseException;
import io.kestra.core.http.client.configurations.HttpConfiguration;
import io.kestra.core.http.client.configurations.TimeoutConfiguration;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;

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
public abstract class AbstractCerebrasConnection extends Task {
    static final String DEFAULT_BASE_URL = "https://api.cerebras.ai/v1";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
    // Maximum idle time while waiting for data: covers a stalled stream as well as a slow non-streamed completion.
    private static final Duration READ_IDLE_TIMEOUT = Duration.ofMinutes(5);

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
    private Property<String> baseUrl = Property.ofValue(DEFAULT_BASE_URL);


    protected HttpRequest.HttpRequestBuilder requestBuilder(RunContext runContext, String method, String path) throws IllegalVariableEvaluationException {
        var rApiKey = runContext.render(this.apiKey).as(String.class)
            .filter(StringUtils::isNotBlank)
            .orElseThrow(() -> new IllegalArgumentException("`apiKey` is required — set it with `{{ secret('CEREBRAS_API_KEY') }}`."));
        var rBaseUrl = runContext.render(this.baseUrl).as(String.class)
            .filter(StringUtils::isNotBlank)
            .orElse(DEFAULT_BASE_URL);

        return HttpRequest.builder()
            .method(method)
            .uri(URI.create(Strings.CS.removeEnd(rBaseUrl, "/") + path))
            .addHeader("Authorization", "Bearer " + rApiKey);
    }

    protected HttpClient httpClient(RunContext runContext) throws IllegalVariableEvaluationException {
        return HttpClient.builder()
            .runContext(runContext)
            .configuration(
                HttpConfiguration.builder()
                    .timeout(
                        TimeoutConfiguration.builder()
                            .connectTimeout(Property.ofValue(CONNECT_TIMEOUT))
                            .readIdleTimeout(Property.ofValue(READ_IDLE_TIMEOUT))
                            .build()
                    )
                    .build()
            )
            .build();
    }


    protected String send(RunContext runContext, HttpRequest request) throws Exception {
        try (var client = httpClient(runContext)) {
            var body = client.request(request, String.class).getBody();
            if (StringUtils.isBlank(body)) {
                throw new IllegalStateException("Empty response from Cerebras API — retry the request, and check the Cerebras status page if the problem persists.");
            }
            return body;
        } catch (HttpClientException e) {
            throw translate(e);
        }
    }

    protected IllegalStateException translate(HttpClientException e) {
        if (e instanceof HttpClientResponseException responseException && responseException.getResponse() != null) {
            var response = responseException.getResponse();
            var code = response.getStatus() != null ? response.getStatus().getCode() : -1;
            var hint = switch (code) {
                case 400 -> "check the request parameters, some may not be supported by the selected model";
                case 401 -> "check that the API key is valid";
                case 403 -> "check that the API key has access to the requested model or resource";
                case 404 -> "check the model name and `baseUrl`";
                case 429 -> "rate limit or quota exceeded, retry later or check your Cerebras plan";
                default -> code >= 500 ? "the Cerebras API is unavailable, retry later" : "check the request";
            };

            return new IllegalStateException("Cerebras API returned HTTP " + code + " — " + hint + ".", e);
        }

        return new IllegalStateException(
            "Unable to reach the Cerebras API — check `baseUrl` and network connectivity: " + e.getMessage(), e
        );
    }
}