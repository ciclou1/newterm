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
    private Embedding embedding = new Embedding();
    private Metrics metrics = new Metrics();

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
        /** Per-HTTP-attempt read timeout. */
        private int timeoutSeconds = 45;
        private int connectTimeoutSeconds = 10;
        /**
         * Hard deadline for one {@code complete(...)} call, covering every
         * attempt, JSON-mode fallback and retry backoff. The lower-level read
         * timeout only bounds a single HTTP attempt.
         */
        private int totalTimeoutSeconds = 60;
        private int maxRetries = 2;
        private long retryBackoffMs = 800;
        /**
         * After a gateway rejects {@code response_format}, JSON mode is skipped
         * for this long and then re-probed. It is never disabled permanently.
         */
        private int jsonModeProbeTtlSeconds = 600;
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

    /**
     * The verified gateway does not expose an embedding endpoint, so the only
     * implemented provider is the deterministic character-hashing model. These
     * knobs exist to make the seam explicit and to fail fast on a provider
     * switch that would need a schema migration.
     */
    @Data
    public static class Embedding {
        private String provider = "hash";
        private int dimensions = 512;
        private String model = "";
    }

    /**
     * Internal scrape credentials. Empty disables the filter entirely; when
     * set, the bearer token can read /actuator/prometheus and nothing else.
     */
    @Data
    public static class Metrics {
        private String scrapeToken = "";
    }
}
