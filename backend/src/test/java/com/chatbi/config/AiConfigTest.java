package com.chatbi.config;

import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.retry.policy.CompositeRetryPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the production wiring, not a hand-built test model: Spring AI's
 * default RetryTemplate performs up to 10 internal attempts, which used to
 * multiply SpringAiLlmClient's outer retries by an order of magnitude.
 */
class AiConfigTest {

    @Test
    void modelBuiltByAiConfigUsesExactlyOneInternalAttempt() throws Exception {
        ChatBiProperties properties = new ChatBiProperties();
        properties.getAi().setEnabled(true);
        properties.getAi().setApiKey("sk-not-a-real-key");
        properties.getAi().setBaseUrl("http://127.0.0.1:1");
        properties.getAi().setModel("deepseek-flash");

        OpenAiChatModel model = new AiConfig().openAiChatModel(properties);

        Field templateField = OpenAiChatModel.class.getDeclaredField("retryTemplate");
        templateField.setAccessible(true);
        RetryTemplate template = (RetryTemplate) templateField.get(model);

        assertThat(template).isNotSameAs(RetryUtils.DEFAULT_RETRY_TEMPLATE);

        Field policyField = RetryTemplate.class.getDeclaredField("retryPolicy");
        policyField.setAccessible(true);
        Object policy = policyField.get(template);
        int maxAttempts;
        if (policy instanceof SimpleRetryPolicy simple) {
            maxAttempts = simple.getMaxAttempts();
        } else if (policy instanceof CompositeRetryPolicy composite) {
            maxAttempts = composite.getMaxAttempts();
        } else {
            throw new AssertionError("unexpected retry policy: " + policy);
        }
        assertThat(maxAttempts).isEqualTo(1);
    }
}
