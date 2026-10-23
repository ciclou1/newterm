package com.chatbi.metrics;

import com.chatbi.nl2sql.AgentResult;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class ChatBiMetrics {

    private final MeterRegistry registry;

    public ChatBiMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void record(AgentResult result) {
        String status = result.status() == null ? "UNKNOWN" : result.status();
        String mode = result.mode() == null ? "UNKNOWN" : result.mode();
        registry.counter("chatbi.queries.total", "status", status, "mode", mode).increment();
        if (result.performance() != null) {
            registry.timer("chatbi.query.duration", "status", status)
                    .record(Duration.ofMillis(Math.max(0, result.performance().totalMs())));
        }
        if (result.usage() != null) {
            registry.summary("chatbi.tokens", "kind", "prompt")
                    .record(result.usage().promptTokens());
            registry.summary("chatbi.tokens", "kind", "completion")
                    .record(result.usage().completionTokens());
            if (result.usage().costUsd() != null) {
                registry.summary("chatbi.cost.usd", "mode", mode)
                        .record(result.usage().costUsd().doubleValue());
            }
        }
        if ("BLOCKED".equals(result.status()) && result.failure() != null) {
            registry.counter("chatbi.sql.blocked", "code",
                    result.failure().code() == null ? "UNKNOWN" : result.failure().code()).increment();
        }
        if ("CLARIFY".equals(result.status())) {
            registry.counter("chatbi.clarifications.total").increment();
        }
    }
}
