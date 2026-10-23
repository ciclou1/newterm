package com.chatbi.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Data
@ConfigurationProperties(prefix = "chatbi")
public class ChatBiProperties {

    private Ai ai = new Ai();
    private Query query = new Query();
    private Security security = new Security();
    private SchemaLink schemaLink = new SchemaLink();

    @Data
    public static class Ai {
        private boolean enabled = false;
        private String baseUrl = "https://api.openai.com";
        private String apiKey = "";
        private String model = "gpt-4.1-mini";
        private double temperature = 0.1;
        private int maxTokens = 1400;
        /**
         * Prices are deliberately not hardcoded: model pricing differs per
         * gateway/account and an invented number would make the cost metric
         * misleading. Zero means "not configured" and reported cost stays 0.
         */
        private BigDecimal inputCostPerMillion = BigDecimal.ZERO;
        private BigDecimal outputCostPerMillion = BigDecimal.ZERO;
        private String priceCurrency = "USD";
        private int timeoutSeconds = 45;
        private int connectTimeoutSeconds = 10;
        private int maxRetries = 2;
        private long retryBackoffMs = 800;
        /**
         * Ask the provider for a JSON object response when supported. If the
         * gateway rejects response_format, the client falls back to plain text
         * with the robust parser instead of failing the query.
         */
        private boolean jsonMode = true;
        /** Total prompt character budget (system + user), estimated before sending. */
        private int maxPromptChars = 28_000;
        /** Cap for the schema/few-shot section inside the system prompt. */
        private int maxSchemaChars = 16_000;
        /** Cap for the conversation-history section inside the user prompt. */
        private int maxHistoryChars = 2_000;
        private int maxHistoryTurns = 3;
        /** Cap for a single user question after sanitisation. */
        private int maxQuestionChars = 2_000;
        /**
         * When AI is enabled but generation fails, falling back to the rule
         * engine must be an explicit opt-in. The default is fail-closed: the
         * user sees an ERROR instead of a plausible-looking wrong answer.
         */
        private boolean allowDemoFallback = false;

        public boolean costConfigured() {
            return inputCostPerMillion != null && outputCostPerMillion != null
                    && (inputCostPerMillion.signum() > 0 || outputCostPerMillion.signum() > 0);
        }
    }

    @Data
    public static class Query {
        private String url = "jdbc:postgresql://localhost:5432/chatbi?stringtype=unspecified";
        private String username = "chatbi_ro";
        private String password = "chatbi_ro";
        private int maxRows = 500;
        private int timeoutSeconds = 8;
        private int explainTimeoutSeconds = 3;
        private long maxExplainCost = 250_000;
        private int maxRepairs = 2;
    }

    @Data
    public static class Security {
        private List<String> allowedOrigins = new ArrayList<>(List.of("http://localhost:5174", "http://localhost:8088"));
        private String defaultUser = "zhangsan";
    }

    @Data
    public static class SchemaLink {
        private int maxTables = 6;
        private int maxColumnsPerTable = 18;
        private int maxMetrics = 6;
        private int maxExamples = 4;
        private int cacheTtlSeconds = 300;
    }
}
