package com.chatbi.config;

import com.chatbi.config.ChatBiProperties.Ai;
import com.chatbi.nl2sql.SpringAiLlmClient;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.retry.support.RetryTemplate;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Manually assembled OpenAI-compatible model. Both beans only exist when AI is
 * explicitly enabled, so a demo deployment without a key still starts cleanly.
 *
 * <p>The LLM client is declared here as a bean instead of being discovered as a
 * {@code @Component} guarded by {@code @ConditionalOnBean}. Bean-level
 * conditions are evaluated while configuration classes are being parsed and are
 * order-dependent; declaring the client next to the model bean makes the
 * dependency explicit and removes the silent "AI enabled but client missing"
 * mode. {@code AiSqlGenerator} additionally fails startup if the property is
 * enabled while no client bean is present.</p>
 */
@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "chatbi.ai", name = "enabled", havingValue = "true")
public class AiConfig {

    @Bean
    public OpenAiChatModel openAiChatModel(ChatBiProperties properties) {
        Ai ai = properties.getAi();
        if (ai.getApiKey() == null || ai.getApiKey().isBlank()) {
            throw new IllegalStateException(
                    "chatbi.ai.enabled=true 但 API Key 为空。请配置 CHATBI_AI_API_KEY，"
                            + "或关闭 AI 使用离线演示模式。");
        }
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, ai.getConnectTimeoutSeconds())))
                .build();
        // JdkClientHttpRequestFactory uses java.net.http.HttpClient.send, which
        // is interruptible: cancelling the deadline future really aborts the
        // in-flight HTTP exchange instead of leaving a socket blocked.
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(Math.max(1, ai.getTimeoutSeconds())));
        RestClient.Builder restClientBuilder = RestClient.builder().requestFactory(requestFactory);

        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(ai.getBaseUrl())
                .apiKey(ai.getApiKey())
                .restClientBuilder(restClientBuilder)
                .build();
        OpenAiChatOptions options = baseOptions(ai);
        OpenAiChatModel model = OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(options)
                // Spring AI's default RetryTemplate performs up to 10 internal
                // attempts with long exponential backoff. SpringAiLlmClient owns
                // retries and the total deadline, so the inner template must be
                // a single attempt to avoid multiplying the worst case.
                .retryTemplate(singleAttemptRetryTemplate())
                .build();
        log.info("AI mode=LLM model={} baseUrl={} temperature={} maxTokens={} jsonMode={} "
                        + "timeout={}s totalTimeout={}s retries={}",
                ai.getModel(), ai.getBaseUrl(), ai.getTemperature(), ai.getMaxTokens(),
                ai.isJsonMode(), ai.getTimeoutSeconds(), ai.getTotalTimeoutSeconds(),
                ai.getMaxRetries());
        if (!ai.costConfigured()) {
            log.warn("AI token prices are not configured; token counts are recorded but cost stays 0. "
                    + "Set CHATBI_AI_INPUT_COST_PER_MILLION / CHATBI_AI_OUTPUT_COST_PER_MILLION "
                    + "from the gateway model card to enable cost reporting.");
        }
        return model;
    }

    @Bean
    public SpringAiLlmClient springAiLlmClient(OpenAiChatModel chatModel,
                                               ChatBiProperties properties,
                                               MeterRegistry meterRegistry) {
        return new SpringAiLlmClient(chatModel, properties.getAi(), meterRegistry);
    }

    /**
     * Exactly one HTTP attempt. Retries and their backoff are owned solely by
     * {@link SpringAiLlmClient}, which also enforces the total deadline.
     */
    static RetryTemplate singleAttemptRetryTemplate() {
        return RetryTemplate.builder().maxAttempts(1).build();
    }

    /**
     * Options without a response format. The per-request JSON-mode option is
     * added by the client so it can be dropped after a provider rejects it.
     */
    static OpenAiChatOptions baseOptions(Ai ai) {
        return OpenAiChatOptions.builder()
                .model(ai.getModel())
                .temperature(ai.getTemperature())
                .maxTokens(ai.getMaxTokens())
                .build();
    }
}
