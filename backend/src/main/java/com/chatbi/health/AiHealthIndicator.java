package com.chatbi.health;

import com.chatbi.config.ChatBiProperties;
import com.chatbi.config.ChatBiProperties.Ai;
import com.chatbi.nl2sql.SpringAiLlmClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Exposes the AI wiring state at {@code /actuator/health} (component {@code ai})
 * and through {@code GET /api/health/ai}. The API key is never part of this
 * snapshot.
 *
 * <p>The indicator is deliberately not a live gateway probe: a health endpoint
 * that performs a paid network call on every scrape is both slow and noisy. It
 * reports whether a client is wired plus the last call's outcome, so failures
 * are visible without generating traffic.</p>
 */
@Component
public class AiHealthIndicator implements HealthIndicator {

    private final ChatBiProperties properties;
    private final ObjectProvider<SpringAiLlmClient> client;

    public AiHealthIndicator(ChatBiProperties properties, ObjectProvider<SpringAiLlmClient> client) {
        this.properties = properties;
        this.client = client;
    }

    @Override
    public Health health() {
        Map<String, Object> details = snapshot();
        Ai ai = properties.getAi();
        if (!ai.isEnabled()) {
            return Health.up().withDetails(details).build();
        }
        SpringAiLlmClient wired = client.getIfAvailable();
        if (wired == null) {
            details.put("error", "AI enabled but no LLM client bean is wired");
            return Health.down().withDetails(details).build();
        }
        return Health.up().withDetails(details).build();
    }

    public Map<String, Object> snapshot() {
        Ai ai = properties.getAi();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("mode", ai.isEnabled() ? "LLM" : "DEMO");
        details.put("model", ai.getModel());
        details.put("baseUrl", ai.getBaseUrl());
        details.put("jsonMode", ai.isJsonMode());
        details.put("maxRetries", ai.getMaxRetries());
        details.put("timeoutSeconds", ai.getTimeoutSeconds());
        details.put("totalTimeoutSeconds", ai.getTotalTimeoutSeconds());
        details.put("jsonModeProbeTtlSeconds", ai.getJsonModeProbeTtlSeconds());
        details.put("costConfigured", ai.costConfigured());
        SpringAiLlmClient wired = client.getIfAvailable();
        details.put("clientAvailable", wired != null);
        if (wired != null) {
            SpringAiLlmClient.Stats stats = wired.stats();
            details.put("jsonModeSupported", stats.jsonModeSupported());
            details.put("calls", stats.calls());
            details.put("successes", stats.successes());
            details.put("failures", stats.failures());
            details.put("retries", stats.retries());
            details.put("jsonFallbacks", stats.jsonFallbacks());
            details.put("httpAttempts", stats.httpAttempts());
            details.put("lastModel", stats.lastModel());
            details.put("lastElapsedMs", stats.lastElapsedMs());
            details.put("lastPromptTokens", stats.lastPromptTokens());
            details.put("lastCompletionTokens", stats.lastCompletionTokens());
            if (stats.lastError() != null) {
                details.put("lastError", stats.lastError());
            }
        }
        return details;
    }
}
