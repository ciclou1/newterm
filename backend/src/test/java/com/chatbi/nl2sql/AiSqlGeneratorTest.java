package com.chatbi.nl2sql;

import com.chatbi.config.ChatBiProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiSqlGeneratorTest {

    @SuppressWarnings("unchecked")
    private final ObjectProvider<SpringAiLlmClient> provider = mock(ObjectProvider.class);

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
}
