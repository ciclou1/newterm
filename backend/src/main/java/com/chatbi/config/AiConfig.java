package com.chatbi.config;

import com.chatbi.config.ChatBiProperties.Ai;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Manually assembled OpenAI-compatible model. It is only created when AI is
 * explicitly enabled, so a demo deployment without a key still starts cleanly.
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
                    "CHATBI_AI_ENABLED=true 但 OPENAI_API_KEY 为空。请配置 API Key，或关闭 AI 使用离线演示模式。");
        }
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(ai.getBaseUrl())
                .apiKey(ai.getApiKey())
                .build();
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(ai.getModel())
                .temperature(ai.getTemperature())
                .maxTokens(ai.getMaxTokens())
                .build();
        OpenAiChatModel model = OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(options)
                .build();
        log.info("Spring AI OpenAI model enabled: baseUrl={}, model={}", ai.getBaseUrl(), ai.getModel());
        return model;
    }
}
