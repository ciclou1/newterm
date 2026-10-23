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
    private Auth auth = new Auth();
    private RateLimit rateLimit = new RateLimit();
    private Quota quota = new Quota();
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
    }

    @Data
    public static class Auth {
        /** HS256 signing key. Minimum 32 bytes; production profiles must supply it. */
        private String jwtSecret = "";
        private String jwtIssuer = "chatbi";
        private long accessTokenTtlSeconds = 3600;
        /**
         * Demo-only escape hatch. When false (the default), X-ChatBI-User is
         * completely ignored and cannot create an authenticated principal.
         */
        private boolean demoHeaderEnabled = false;
        /** Shared secret used by the stdio MCP server. Empty disables service auth. */
        private String serviceToken = "";
        private String serviceUsername = "mcp-service";
        /**
         * Production first-start credential injection. In docker/prod, if the
         * bootstrap account still has the seeded demo password (or no
         * password), this value is required and becomes its BCrypt hash.
         * Existing non-demo passwords are never overwritten.
         */
        private String bootstrapAdminUsername = "lisi";
        private String bootstrapAdminPassword = "";
    }

    @Data
    public static class RateLimit {
        private boolean enabled = true;
        /** Token-bucket refill rate for expensive NL2SQL requests. */
        private int requestsPerMinute = 10;
        /** Burst capacity; defaults to requestsPerMinute when <= 0. */
        private int burstCapacity = 0;
        /** Also protect the login endpoint against password guessing. */
        private int loginRequestsPerMinute = 20;
        /**
         * Whether X-Forwarded-For / X-Real-IP may identify the client. Leave
         * false unless the backend is only reachable through a trusted proxy
         * that overwrites those headers.
         */
        private boolean trustForwardedHeaders = false;
        /** Failed logins for one username before a temporary lock. */
        private int loginFailureThreshold = 5;
        /** Window in which failures are counted. */
        private int loginFailureWindowSeconds = 900;
        /** How long a username stays locked after too many failures. */
        private int loginLockSeconds = 900;
    }

    @Data
    public static class Quota {
        /** Per-user daily token budget (prompt + completion). 0 disables the check. */
        private long dailyTokenLimit = 2_000_000;
        /** Per-user daily estimated model cost in USD. 0 disables the check. */
        private BigDecimal dailyCostLimitUsd = new BigDecimal("5.00");
        /**
         * Tokens reserved per in-flight request so concurrent requests cannot
         * all pass the same check. Actual usage is still read from query_audit.
         */
        private long reserveTokens = 4096;
        /** Cost in USD reserved per in-flight request. */
        private BigDecimal reserveCostUsd = new BigDecimal("0.05");
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
