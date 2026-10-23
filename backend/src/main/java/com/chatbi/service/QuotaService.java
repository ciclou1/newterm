package com.chatbi.service;

import com.chatbi.common.ChatBiException;
import com.chatbi.config.ChatBiProperties;
import com.chatbi.metrics.ChatBiMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Per-user daily cost/quota guard. Usage is derived from the audit table, which
 * is the same source the cost dashboards read. A database failure fails closed:
 * an unenforceable budget must not silently allow spend.
 */
@Slf4j
@Service
public class QuotaService {

    private static final String USAGE_SQL = """
            SELECT COALESCE(SUM(prompt_tokens + completion_tokens), 0) AS tokens,
                   COALESCE(SUM(cost_usd), 0) AS cost_usd
            FROM query_audit
            WHERE user_id = ? AND created_at >= date_trunc('day', now())
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ChatBiProperties properties;
    private final ChatBiMetrics metrics;

    public QuotaService(JdbcTemplate jdbcTemplate, ChatBiProperties properties, ChatBiMetrics metrics) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.metrics = metrics;
    }

    public void check(String username) {
        ChatBiProperties.Quota quota = properties.getQuota();
        boolean tokenLimitEnabled = quota.getDailyTokenLimit() > 0;
        boolean costLimitEnabled = quota.getDailyCostLimitUsd() != null
                && quota.getDailyCostLimitUsd().compareTo(BigDecimal.ZERO) > 0;
        if (!tokenLimitEnabled && !costLimitEnabled) {
            return;
        }
        Usage usage = usage(username);
        if (tokenLimitEnabled && usage.tokens() >= quota.getDailyTokenLimit()) {
            metrics.quotaRejected("tokens");
            throw ChatBiException.tooManyRequests("DAILY_TOKEN_QUOTA_EXCEEDED",
                    "今日 Token 配额已用完，请明天再试或联系管理员");
        }
        if (costLimitEnabled && usage.costUsd().compareTo(quota.getDailyCostLimitUsd()) >= 0) {
            metrics.quotaRejected("cost");
            throw ChatBiException.tooManyRequests("DAILY_COST_QUOTA_EXCEEDED",
                    "今日模型成本配额已用完，请明天再试或联系管理员");
        }
    }

    public Usage usage(String username) {
        try {
            Map<String, Object> row = jdbcTemplate.queryForMap(USAGE_SQL, username);
            long tokens = row.get("tokens") instanceof Number value ? value.longValue() : 0L;
            BigDecimal cost = row.get("cost_usd") instanceof BigDecimal value
                    ? value : new BigDecimal(String.valueOf(row.getOrDefault("cost_usd", 0)));
            return new Usage(tokens, cost);
        } catch (DataAccessException e) {
            log.error("Quota usage query failed for user {}: {}", username, e.getMessage());
            throw ChatBiException.serviceUnavailable("QUOTA_UNAVAILABLE",
                    "暂时无法校验配额，请稍后重试");
        }
    }

    public record Usage(long tokens, BigDecimal costUsd) {
    }
}
