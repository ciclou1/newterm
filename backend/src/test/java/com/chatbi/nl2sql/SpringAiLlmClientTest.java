package com.chatbi.nl2sql;

import com.chatbi.config.ChatBiProperties.Ai;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import io.micrometer.core.instrument.Counter;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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
    private SimpleMeterRegistry registry;
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
        registry = new SimpleMeterRegistry();
        client = new SpringAiLlmClient(model, ai, registry);
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
        assertThat(client.stats().httpAttempts()).isEqualTo(1);
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
        assertThat(client.stats().httpAttempts()).isEqualTo(3);
        assertThat(client.stats().successes()).isEqualTo(1);
        assertThat(registry.counter("chatbi.llm.http.attempts", "model", "deepseek-flash").count())
                .isEqualTo(3.0);
    }

    @Test
    void givesUpAfterConfiguredRetriesAndRecordsFailure() {
        when(api.chatCompletionEntity(any(OpenAiApi.ChatCompletionRequest.class), any()))
                .thenThrow(new TransientAiException("503 Service Unavailable"));

        assertThatThrownBy(() -> client.complete("system", "user"))
                .isInstanceOf(TransientAiException.class);

        assertThat(client.stats().calls()).isEqualTo(3);
        assertThat(client.stats().retries()).isEqualTo(2);
        assertThat(client.stats().httpAttempts()).isEqualTo(3);
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
    void fallsBackWhenOnlyTheResponseBodyMentionsResponseFormat() {
        byte[] body = "{\"error\":{\"message\":\"response_format is not supported\"}}"
                .getBytes(StandardCharsets.UTF_8);
        RestClientResponseException rejected = new RestClientResponseException(
                "400 Bad Request", 400, "Bad Request", HttpHeaders.EMPTY, body,
                StandardCharsets.UTF_8);
        when(api.chatCompletionEntity(any(OpenAiApi.ChatCompletionRequest.class), any()))
                .thenThrow(rejected)
                .thenReturn(ResponseEntity.ok(recorded));

        client.complete("system", "user");

        assertThat(client.stats().jsonFallbacks()).isEqualTo(1);
    }

    @Test
    void doesNotTreatContextLength400AsJsonModeRejection() {
        RestClientResponseException tooLong = new RestClientResponseException(
                "400 Bad Request: maximum context length exceeded", 400, "Bad Request",
                HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8);
        when(api.chatCompletionEntity(any(OpenAiApi.ChatCompletionRequest.class), any()))
                .thenThrow(tooLong);

        assertThatThrownBy(() -> client.complete("system", "user"))
                .isSameAs(tooLong);

        assertThat(client.stats().calls()).isEqualTo(1);
        assertThat(client.stats().jsonFallbacks()).isZero();
        assertThat(client.stats().jsonModeSupported()).isTrue();
    }

    @Test
    void doesNotTreatMissingModel404AsJsonModeRejection() {
        RestClientResponseException missing = new RestClientResponseException(
                "404 Not Found: model deepseek-flash does not exist", 404, "Not Found",
                HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8);
        when(api.chatCompletionEntity(any(OpenAiApi.ChatCompletionRequest.class), any()))
                .thenThrow(missing);

        assertThatThrownBy(() -> client.complete("system", "user")).isSameAs(missing);

        assertThat(client.stats().calls()).isEqualTo(1);
        assertThat(client.stats().jsonFallbacks()).isZero();
        assertThat(client.stats().jsonModeSupported()).isTrue();
    }

    @Test
    void doesNotTreatInvalidRequest422AsJsonModeRejection() {
        RestClientResponseException invalid = new RestClientResponseException(
                "422 Unprocessable Entity: messages must not be empty", 422, "Unprocessable Entity",
                HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8);
        when(api.chatCompletionEntity(any(OpenAiApi.ChatCompletionRequest.class), any()))
                .thenThrow(invalid);

        assertThatThrownBy(() -> client.complete("system", "user")).isSameAs(invalid);

        assertThat(client.stats().calls()).isEqualTo(1);
        assertThat(client.stats().jsonFallbacks()).isZero();
        assertThat(client.stats().jsonModeSupported()).isTrue();
    }

    @Test
    void subsequentCallsSkipJsonModeOnlyUntilTheProbeTtlExpires() {
        ai.setJsonModeProbeTtlSeconds(0);
        RestClientResponseException rejected = new RestClientResponseException(
                "response_format json_object is not supported", 400, "Bad Request",
                HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8);
        when(api.chatCompletionEntity(any(OpenAiApi.ChatCompletionRequest.class), any()))
                .thenThrow(rejected)
                .thenReturn(ResponseEntity.ok(recorded))
                .thenReturn(ResponseEntity.ok(recorded));

        client.complete("system", "user");
        client.complete("system", "user");

        ArgumentCaptor<OpenAiApi.ChatCompletionRequest> captor =
                ArgumentCaptor.forClass(OpenAiApi.ChatCompletionRequest.class);
        verify(api, times(3)).chatCompletionEntity(captor.capture(), any());
        assertThat(captor.getAllValues().get(0).responseFormat()).isNotNull();
        assertThat(captor.getAllValues().get(1).responseFormat()).isNull();
        assertThat(captor.getAllValues().get(2).responseFormat()).isNotNull();
    }

    @Test
    void keepsSkippingJsonModeWithinTheDefaultTtl() {
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
        assertThat(captor.getAllValues().get(1).responseFormat()).isNull();
        assertThat(captor.getAllValues().get(2).responseFormat()).isNull();
    }

    @Test
    void abortsAtTheTotalDeadlineAndInterruptsTheHttpAttempt() throws Exception {
        ai.setTotalTimeoutSeconds(1);
        ai.setMaxRetries(5);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        AtomicReference<String> workerState = new AtomicReference<>("not-started");
        when(api.chatCompletionEntity(any(OpenAiApi.ChatCompletionRequest.class), any()))
                .thenAnswer(invocation -> {
                    workerState.set("started");
                    started.countDown();
                    try {
                        Thread.sleep(5_000);
                    } catch (InterruptedException e) {
                        interrupted.countDown();
                        workerState.set("interrupted");
                        throw new IllegalStateException("cancelled", e);
                    }
                    return ResponseEntity.ok(recorded);
                });

        long startedAt = System.currentTimeMillis();
        assertThatThrownBy(() -> client.complete("system", "user"))
                .isInstanceOf(SpringAiLlmClient.LlmTimeoutException.class)
                .hasMessageContaining("AI_TIMEOUT");
        long elapsed = System.currentTimeMillis() - startedAt;

        assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(interrupted.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(workerState.get()).isEqualTo("interrupted");
        assertThat(elapsed).isLessThan(3_000);
        assertThat(client.stats().calls()).isEqualTo(1);
        assertThat(client.stats().httpAttempts()).isEqualTo(1);
        assertThat(client.stats().failures()).isEqualTo(1);
        assertThat(client.stats().lastError()).contains("AI_TIMEOUT");
    }

    @Test
    void honoursRetryAfterHeaderOn429() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, "1");
        RestClientResponseException rateLimited = new RestClientResponseException(
                "429 Too Many Requests", 429, "Too Many Requests", headers,
                new byte[0], StandardCharsets.UTF_8);
        when(api.chatCompletionEntity(any(OpenAiApi.ChatCompletionRequest.class), any()))
                .thenThrow(rateLimited)
                .thenReturn(ResponseEntity.ok(recorded));

        long startedAt = System.currentTimeMillis();
        LlmResponse response = client.complete("system", "user");
        long elapsed = System.currentTimeMillis() - startedAt;

        assertThat(response.promptTokens()).isEqualTo(612);
        assertThat(elapsed).isGreaterThanOrEqualTo(900);
        assertThat(client.stats().retries()).isEqualTo(1);
        assertThat(client.stats().httpAttempts()).isEqualTo(2);
    }

    @Test
    void redactsCredentialShapedTextFromGatewayErrors() {
        String message = "401 Unauthorized: invalid key sk-test-1234567890, "
                + "authorization Bearer abcdefghijkl";
        RestClientResponseException unauthorized = new RestClientResponseException(
                message, 401, "Unauthorized", HttpHeaders.EMPTY,
                "{\"error\":\"sk-test-1234567890\"}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
        when(api.chatCompletionEntity(any(OpenAiApi.ChatCompletionRequest.class), any()))
                .thenThrow(unauthorized);

        assertThatThrownBy(() -> client.complete("system", "user")).isSameAs(unauthorized);

        String lastError = client.stats().lastError();
        assertThat(lastError).contains("sk-***").contains("Bearer ***");
        assertThat(lastError).doesNotContain("sk-test-1234567890")
                .doesNotContain("abcdefghijkl");
    }

    @Test
    void metricsUseConfiguredModelInsteadOfGatewayResponseMetadata() {
        ai.setModel("configured-model");
        when(api.chatCompletionEntity(any(OpenAiApi.ChatCompletionRequest.class), any()))
                .thenReturn(ResponseEntity.ok(recorded));

        client.complete("system", "user");

        Counter configured = registry.find("chatbi.llm.calls")
                .tag("model", "configured-model").tag("outcome", "success").counter();
        Counter gatewayModel = registry.find("chatbi.llm.calls")
                .tag("model", "deepseek-flash").tag("outcome", "success").counter();
        assertThat(configured).isNotNull();
        assertThat(configured.count()).isEqualTo(1.0);
        assertThat(gatewayModel).isNull();
        assertThat(registry.find("chatbi.llm.http.attempts")
                .tag("model", "configured-model").counter()).isNotNull();
    }
}
