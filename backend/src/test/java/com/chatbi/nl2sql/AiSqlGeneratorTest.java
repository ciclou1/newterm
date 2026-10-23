package com.chatbi.nl2sql;

import com.chatbi.config.ChatBiProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiSqlGeneratorTest {

    @SuppressWarnings("unchecked")
    private final ObjectProvider<SpringAiLlmClient> provider = mock(ObjectProvider.class);
    private final PromptBuilder promptBuilder = mock(PromptBuilder.class);

    @Test
    void failsStartupWhenAiIsEnabledButClientIsMissing() {
        ChatBiProperties properties = new ChatBiProperties();
        properties.getAi().setEnabled(true);
        when(provider.getIfAvailable()).thenReturn(null);
        AiSqlGenerator generator = new AiSqlGenerator(provider,
                new PromptBuilder(properties), new ObjectMapper(), properties);

        assertThatThrownBy(generator::verifyConfiguration)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未装配 LLM 客户端");
    }

    @Test
    void demoModeStartsWithoutAClient() {
        ChatBiProperties properties = new ChatBiProperties();
        properties.getAi().setEnabled(false);
        when(provider.getIfAvailable()).thenReturn(null);
        AiSqlGenerator generator = new AiSqlGenerator(provider,
                new PromptBuilder(properties), new ObjectMapper(), properties);

        assertThatCode(generator::verifyConfiguration).doesNotThrowAnyException();
        assertThat(generator.available()).isFalse();
    }

    @Test
    void redactsSecretsBeforeTheyReachUserVisibleErrors() {
        ChatBiProperties properties = new ChatBiProperties();
        SpringAiLlmClient client = mock(SpringAiLlmClient.class);
        when(provider.getIfAvailable()).thenReturn(client);
        when(promptBuilder.build(any(), any(), any(), any(), any(), any()))
                .thenReturn(new PromptBuilder.BuiltPrompt("system", "user", 10, 5));
        when(client.complete("system", "user")).thenThrow(new IllegalStateException(
                "401 Unauthorized: invalid key sk-test-1234567890, "
                        + "authorization Bearer abcdefghijkl"));
        AiSqlGenerator generator = new AiSqlGenerator(provider, promptBuilder,
                new ObjectMapper(), properties);

        SqlGeneration generation = generator.generate("问题", null, null, List.of(), null, null);

        assertThat(generation.error()).contains("sk-***").contains("Bearer ***")
                .doesNotContain("sk-test-1234567890")
                .doesNotContain("abcdefghijkl");
    }

    @Test
    void surfacesTimeoutAsAnExplicitAiTimeoutError() {
        ChatBiProperties properties = new ChatBiProperties();
        SpringAiLlmClient client = mock(SpringAiLlmClient.class);
        when(provider.getIfAvailable()).thenReturn(client);
        when(promptBuilder.build(any(), any(), any(), any(), any(), any()))
                .thenReturn(new PromptBuilder.BuiltPrompt("system", "user", 10, 5));
        when(client.complete("system", "user")).thenThrow(
                new SpringAiLlmClient.LlmTimeoutException("AI_TIMEOUT: LLM 请求超过总时限 60s", null));
        AiSqlGenerator generator = new AiSqlGenerator(provider, promptBuilder,
                new ObjectMapper(), properties);

        SqlGeneration generation = generator.generate("问题", null, null, List.of(), null, null);

        assertThat(generation.error()).startsWith("AI_TIMEOUT");
        assertThat(generation.error()).doesNotContain("模型调用失败");
    }
}
