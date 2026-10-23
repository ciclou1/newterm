package com.chatbi.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Redis-backed quota reservations. The check and the increment happen in one
 * Lua script, so concurrent requests for the same user cannot both observe
 * "budget left" and overspend. Actual usage still comes from query_audit; the
 * reservation only covers requests that are currently in flight.
 */
@Component
public class RedisQuotaReservationStore implements QuotaReservationStore {

    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;
    private static final ZoneId QUOTA_ZONE = ZoneId.of("Asia/Shanghai");

    private static final String RESERVE_LUA = """
            local spentTokens = tonumber(ARGV[1])
            local spentCost = tonumber(ARGV[2])
            local wantTokens = tonumber(ARGV[3])
            local wantCost = tonumber(ARGV[4])
            local tokenLimit = tonumber(ARGV[5])
            local costLimit = tonumber(ARGV[6])
            local ttl = tonumber(ARGV[7])
            local reservedTokens = tonumber(redis.call('GET', KEYS[1]) or '0')
            local reservedCost = tonumber(redis.call('GET', KEYS[2]) or '0')
            local addTokens = 0
            local addCost = 0
            if tokenLimit > 0 then
              local remaining = tokenLimit - spentTokens - reservedTokens
              if remaining <= 0 then
                return {2, 0, 0}
              end
              addTokens = math.min(wantTokens, remaining)
            end
            if costLimit > 0 then
              local remaining = costLimit - spentCost - reservedCost
              if remaining <= 0 then
                return {3, 0, 0}
              end
              addCost = math.min(wantCost, remaining)
            end
            if addTokens > 0 then
              redis.call('INCRBY', KEYS[1], addTokens)
              redis.call('EXPIRE', KEYS[1], ttl)
            end
            if addCost > 0 then
              redis.call('INCRBY', KEYS[2], addCost)
              redis.call('EXPIRE', KEYS[2], ttl)
            end
            return {1, addTokens, addCost}
            """;

    private static final String RELEASE_LUA = """
            local held = tonumber(redis.call('GET', KEYS[1]) or '0')
            local give = tonumber(ARGV[1])
            if held - give <= 0 then
              redis.call('DEL', KEYS[1])
            else
              redis.call('DECRBY', KEYS[1], give)
            end
            return 1
            """;

    private static final RedisScript<List> RESERVE_SCRIPT =
            new DefaultRedisScript<>(RESERVE_LUA, List.class);
    private static final RedisScript<Long> RELEASE_SCRIPT =
            new DefaultRedisScript<>(RELEASE_LUA, Long.class);

    private final StringRedisTemplate redis;

    public RedisQuotaReservationStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Result tryReserve(String username, long spentTokens, long spentCostMicros,
                             long tokenLimit, long costLimitMicros,
                             long reserveTokens, long reserveCostMicros) {
        LocalDateTime now = LocalDateTime.now(QUOTA_ZONE);
        String day = now.format(DAY);
        long ttl = Math.max(3600L,
                Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay()).getSeconds() + 3600);
        List<?> raw = redis.execute(RESERVE_SCRIPT, keys(username, day),
                String.valueOf(spentTokens),
                String.valueOf(spentCostMicros),
                String.valueOf(Math.max(0, reserveTokens)),
                String.valueOf(Math.max(0, reserveCostMicros)),
                String.valueOf(Math.max(0, tokenLimit)),
                String.valueOf(Math.max(0, costLimitMicros)),
                String.valueOf(ttl));
        if (raw == null || raw.size() < 3) {
            throw new IllegalStateException("Unexpected quota reservation response from Redis");
        }
        long code = asLong(raw.get(0));
        if (code == 2) {
            return Result.rejected(Status.TOKEN_EXCEEDED);
        }
        if (code == 3) {
            return Result.rejected(Status.COST_EXCEEDED);
        }
        if (code != 1) {
            throw new IllegalStateException("Unexpected quota reservation code: " + code);
        }
        return Result.acquired(asLong(raw.get(1)), asLong(raw.get(2)), day);
    }

    @Override
    public void release(String username, String day, long tokens, long costMicros) {
        if (day == null || (tokens <= 0 && costMicros <= 0)) {
            return;
        }
        List<String> keys = keys(username, day);
        if (tokens > 0) {
            redis.execute(RELEASE_SCRIPT, List.of(keys.get(0)), String.valueOf(tokens));
        }
        if (costMicros > 0) {
            redis.execute(RELEASE_SCRIPT, List.of(keys.get(1)), String.valueOf(costMicros));
        }
    }

    private static List<String> keys(String username, String day) {
        String prefix = "chatbi:quota:" + day + ":" + username;
        return List.of(prefix + ":tokens", prefix + ":cost-micros");
    }

    private static long asLong(Object value) {
        return value instanceof Number number ? number.longValue()
                : Long.parseLong(String.valueOf(value));
    }
}
