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
        private BigDecimal inputCostPerMillion = new BigDecimal("0.40");
        private BigDecimal outputCostPerMillion = new BigDecimal("1.60");
        private int timeoutSeconds = 45;
        /**
         * When AI is enabled but generation fails, falling back to the rule
         * engine must be an explicit opt-in. The default is fail-closed: the
         * user sees an ERROR instead of a plausible-looking wrong answer.
         */
        private boolean allowDemoFallback = false;
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
