package com.chatbi.nl2sql;

import com.chatbi.config.ChatBiProperties.Ai;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpringAiLlmClientTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private Ai ai;
    private OpenAiApi api;
    private SpringAiLlmClient client;
    private OpenAiApi.ChatCompletion recorded;

    @BeforeEach
    void setUp() throws Exception {
        ai = new Ai();
        ai.setModel("deepseek-flash");
        ai.setJsonMode(true);
        ai.setMaxRetries(2);
        ai.setRetryBackoffMs(1);
        ai.setTimeoutSeconds(5);
        api = mock(OpenAiApi.class);
        OpenAiChatModel model = OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder().model("deepseek-flash").build())
                .retryTemplate(org.springframework.retry.support.RetryTemplate.builder().maxAttempts(1).build())
                .build();
        client = new SpringAiLlmClient(model, ai, new SimpleMeterRegistry());
        try (InputStream input = new ClassPathResource("llm/deepseek-chat-completion.json").getInputStream()) {
            recorded = mapper.readValue(input, OpenAiApi.ChatCompletion.class);
        }
    }

    @Test
    void usesOnlyContentAndNeverReasoningContent() {
        when(api.chatCompletionEntity(any(OpenAiApi.ChatCompletionRequest.class), any()))
                .thenReturn(ResponseEntity.ok(recorded));

        LlmResponse response = client.complete("system", "user");

        assertThat(response.text()).startsWith("{\"sql\":\"SELECT SUM(amount)");
        assertThat(response.text()).doesNotContain("reasoning");
        assertThat(response.text()).doesNotContain("DELETE FROM");
        assertThat(response.promptTokens()).isEqualTo(612);
        assertThat(response.completionTokens()).isEqualTo(127);
        assertThat(response.model()).isEqualTo("deepseek-flash");
        assertThat(client.stats().successes()).isEqualTo(1);
        assertThat(client.stats().failures()).isZero();
    }

    @Test
    void requestsJsonObjectResponseFormatWhenEnabled() {
        when(api.chatCompletionEntity(any(OpenAiApi.ChatCompletionRequest.class), any()))
                .thenReturn(ResponseEntity.ok(recorded));

        client.complete("system", "user");

        ArgumentCaptor<OpenAiApi.ChatCompletionRequest> captor =
                ArgumentCaptor.forClass(OpenAiApi.ChatCompletionRequest.class);
        verify(api).chatCompletionEntity(captor.capture(), any());
        assertThat(captor.getValue().responseFormat()).isNotNull();
        assertThat(captor.getValue().responseFormat().getType()).isEqualTo(ResponseFormat.Type.JSON_OBJECT);
    }

    @Test
    void retriesTransientFailuresWithBackoffThenSucceeds() {
        when(api.chatCompletionEntity(any(OpenAiApi.ChatCompletionRequest.class), any()))
                .thenThrow(new TransientAiException("429 Too Many Requests"))
                .thenThrow(new ResourceAccessException("Read timed out"))
                .thenReturn(ResponseEntity.ok(recorded));

        LlmResponse response = client.complete("system", "user");

        assertThat(response.promptTokens()).isEqualTo(612);
        assertThat(client.stats().calls()).isEqualTo(3);
        assertThat(client.stats().retries()).isEqualTo(2);
        assertThat(client.stats().successes()).isEqualTo(1);
    }

    @Test
    void givesUpAfterConfiguredRetriesAndRecordsFailure() {
        when(api.chatCompletionEntity(any(OpenAiApi.ChatCompletionRequest.class), any()))
                .thenThrow(new TransientAiException("503 Service Unavailable"));

        assertThatThrownBy(() -> client.complete("system", "user"))
                .isInstanceOf(TransientAiException.class);

        assertThat(client.stats().calls()).isEqualTo(3);
        assertThat(client.stats().retries()).isEqualTo(2);
        assertThat(client.stats().failures()).isEqualTo(1);
        assertThat(client.stats().lastError()).contains("503");
        assertThat(client.stats().lastError()).doesNotContain("api_key").doesNotContain("sk-");
    }

    @Test
    void doesNotRetryNonTransientClientErrors() {
        when(api.chatCompletionEntity(any(OpenAiApi.ChatCompletionRequest.class), any()))
                .thenThrow(new NonTransientAiException("400 Bad Request: invalid request payload"));

        assertThatThrownBy(() -> client.complete("system", "user"))
                .isInstanceOf(NonTransientAiException.class);

        assertThat(client.stats().calls()).isEqualTo(1);
        assertThat(client.stats().retries()).isZero();
    }

    @Test
    void fallsBackToPlainTextWhenGatewayRejectsJsonMode() {
        RestClientResponseException rejected = new RestClientResponseException(
                "response_format json_object is not supported", 400, "Bad Request",
                HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8);
        when(api.chatCompletionEntity(any(OpenAiApi.ChatCompletionRequest.class), any()))
                .thenThrow(rejected)
                .thenReturn(ResponseEntity.ok(recorded));

        LlmResponse response = client.complete("system", "user");

        assertThat(response.text()).contains("\"sql\"");
        assertThat(client.stats().jsonFallbacks()).isEqualTo(1);
        assertThat(client.stats().jsonModeSupported()).isFalse();
        assertThat(client.stats().successes()).isEqualTo(1);
        assertThat(client.stats().retries()).isZero();
    }

    @Test
    void subsequentCallsSkipJsonModeAfterProviderRejection() {
        RestClientResponseException rejected = new RestClientResponseException(
                "response_format json_object is not supported", 400, "Bad Request",
                HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8);
        when(api.chatCompletionEntity(any(OpenAiApi.ChatCompletionRequest.class), any()))
                .thenThrow(rejected)
                .thenReturn(ResponseEntity.ok(recorded));

        client.complete("system", "user");
        client.complete("system", "user");

        ArgumentCaptor<OpenAiApi.ChatCompletionRequest> captor =
                ArgumentCaptor.forClass(OpenAiApi.ChatCompletionRequest.class);
        verify(api, times(3)).chatCompletionEntity(captor.capture(), any());
        assertThat(captor.getAllValues().get(0).responseFormat()).isNotNull();
        assertThat(captor.getAllValues().get(1).responseFormat()).isNull();
        assertThat(captor.getAllValues().get(2).responseFormat()).isNull();
    }
}
