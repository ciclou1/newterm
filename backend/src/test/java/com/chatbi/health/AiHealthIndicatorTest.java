package com.chatbi.health;

import com.chatbi.config.ChatBiProperties;
import com.chatbi.nl2sql.SpringAiLlmClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiHealthIndicatorTest {

    @SuppressWarnings("unchecked")
    private final ObjectProvider<SpringAiLlmClient> provider = mock(ObjectProvider.class);

    @Test
    void reportsDemoModeWhenAiIsDisabled() {
        ChatBiProperties properties = new ChatBiProperties();
        when(provider.getIfAvailable()).thenReturn(null);
        AiHealthIndicator indicator = new AiHealthIndicator(properties, provider);

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("mode", "DEMO")
                .containsEntry("clientAvailable", false);
    }

    @Test
    void reportsDownWhenEnabledButClientIsNotWired() {
        ChatBiProperties properties = new ChatBiProperties();
        properties.getAi().setEnabled(true);
        when(provider.getIfAvailable()).thenReturn(null);
        AiHealthIndicator indicator = new AiHealthIndicator(properties, provider);

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("mode", "LLM")
                .containsEntry("clientAvailable", false);
    }

    @Test
    void exposesCallStatisticsWithoutSecrets() {
        ChatBiProperties properties = new ChatBiProperties();
        properties.getAi().setEnabled(true);
        properties.getAi().setModel("deepseek-flash");
        properties.getAi().setBaseUrl("https://gateway.example");
        properties.getAi().setApiKey("sk-must-never-appear");
        SpringAiLlmClient client = mock(SpringAiLlmClient.class);
        when(provider.getIfAvailable()).thenReturn(client);
        when(client.stats()).thenReturn(new SpringAiLlmClient.Stats(true, "deepseek-flash",
                "https://gateway.example", true, 4, 3, 1, 2, 0, 5, "deepseek-flash",
                "503 Service Unavailable", 1200, 900, 40));
        AiHealthIndicator indicator = new AiHealthIndicator(properties, provider);

        Health health = indicator.health();
        String details = health.getDetails().toString();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(details).contains("deepseek-flash")
                .contains("503 Service Unavailable")
                .contains("retries=2")
                .contains("httpAttempts=5");
        assertThat(details).doesNotContain("sk-must-never-appear");
    }
}
