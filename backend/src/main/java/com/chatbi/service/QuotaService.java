package com.chatbi.service;

import com.chatbi.common.ChatBiException;
import com.chatbi.config.ChatBiProperties;
import com.chatbi.metrics.ChatBiMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/**
 * Per-user daily cost/quota guard. Usage is derived from the audit table, which
 * is the same source the cost dashboards read; the difference from a plain
 * check-then-act guard is that each in-flight request also holds an atomic
 * reservation in Redis, so N concurrent requests cannot all pass on the same
 * remaining budget.
 *
 * <p>A database failure fails closed: an unenforceable budget must not silently
 * allow spend. A Redis failure does the same, because without the shared
 * reservation the concurrency guarantee no longer holds.</p>
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

    private static final BigDecimal MICROS = new BigDecimal(1_000_000);

    private final JdbcTemplate jdbcTemplate;
    private final ChatBiProperties properties;
    private final ChatBiMetrics metrics;
    private final QuotaReservationStore reservationStore;

    public QuotaService(JdbcTemplate jdbcTemplate, ChatBiProperties properties,
                        ChatBiMetrics metrics, QuotaReservationStore reservationStore) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.metrics = metrics;
        this.reservationStore = reservationStore;
    }

    /**
     * Atomically reserve part of today's remaining budget. The caller must
     * release the reservation in a finally block once the request finishes.
     */
    public Reservation reserve(String username) {
        ChatBiProperties.Quota quota = properties.getQuota();
        boolean tokenLimitEnabled = quota.getDailyTokenLimit() > 0;
        boolean costLimitEnabled = quota.getDailyCostLimitUsd() != null
                && quota.getDailyCostLimitUsd().compareTo(BigDecimal.ZERO) > 0;
        if (!tokenLimitEnabled && !costLimitEnabled) {
            return Reservation.none(username);
        }
        Usage usage = usage(username);
        long reserveTokens = tokenLimitEnabled ? Math.max(0L, quota.getReserveTokens()) : 0L;
        long reserveCostMicros = costLimitEnabled
                ? Math.max(0L, toMicros(quota.getReserveCostUsd())) : 0L;
        QuotaReservationStore.Result result;
        try {
            result = reservationStore.tryReserve(username, usage.tokens(), toMicros(usage.costUsd()),
                    tokenLimitEnabled ? quota.getDailyTokenLimit() : 0L,
                    costLimitEnabled ? toMicros(quota.getDailyCostLimitUsd()) : 0L,
                    reserveTokens, reserveCostMicros);
        } catch (RuntimeException e) {
            log.error("Quota reservation failed for user {}: {}", username, e.getMessage());
            throw ChatBiException.serviceUnavailable("QUOTA_UNAVAILABLE",
                    "暂时无法校验配额，请稍后重试");
        }
        if (result.status() == QuotaReservationStore.Status.TOKEN_EXCEEDED) {
            metrics.quotaRejected("tokens");
            throw ChatBiException.tooManyRequests("DAILY_TOKEN_QUOTA_EXCEEDED",
                    "今日 Token 配额已用完，请明天再试或联系管理员");
        }
        if (result.status() == QuotaReservationStore.Status.COST_EXCEEDED) {
            metrics.quotaRejected("cost");
            throw ChatBiException.tooManyRequests("DAILY_COST_QUOTA_EXCEEDED",
                    "今日模型成本配额已用完，请明天再试或联系管理员");
        }
        return new Reservation(username, result.tokens(), result.costMicros(), result.day(), true);
    }

    /** Return an acquired reservation. Never fails the response that already ran. */
    public void release(Reservation reservation) {
        if (reservation == null || !reservation.active()) {
            return;
        }
        try {
            reservationStore.release(reservation.username(), reservation.day(),
                    reservation.tokens(), reservation.costMicros());
        } catch (RuntimeException e) {
            // The reservation expires with the day key; a failed refund must not
            // turn a successful query into an error.
            log.warn("Failed to release quota reservation for {}: {}",
                    reservation.username(), e.getMessage());
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

    private static long toMicros(BigDecimal value) {
        if (value == null) {
            return 0L;
        }
        return value.multiply(MICROS).setScale(0, RoundingMode.HALF_UP).longValue();
    }

    public record Usage(long tokens, BigDecimal costUsd) {
    }

    public record Reservation(String username, long tokens, long costMicros,
                              String day, boolean active) {
        static Reservation none(String username) {
            return new Reservation(username, 0, 0, null, false);
        }
    }
}
