package com.chatbi.security;

import com.chatbi.config.ChatBiProperties;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * In-memory token buckets. This is intentionally per-instance: a deployment
 * with more than one backend replica must move the buckets to Redis so the
 * limit is shared.
 */
@Component
public class TokenBucketRateLimiter {

    private static final long IDLE_EVICT_NANOS = 10 * 60 * 1_000_000_000L;
    private static final int MAX_BUCKETS = 10_000;

    private final ChatBiProperties properties;
    private final LongSupplier nanoClock;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public TokenBucketRateLimiter(ChatBiProperties properties) {
        this(properties, System::nanoTime);
    }

    TokenBucketRateLimiter(ChatBiProperties properties, LongSupplier nanoClock) {
        this.properties = properties;
        this.nanoClock = nanoClock;
    }

    public boolean tryAcquire(String scope, String subject, int requestsPerMinute, int burstCapacity) {
        if (!properties.getRateLimit().isEnabled() || requestsPerMinute <= 0) {
            return true;
        }
        int capacity = burstCapacity > 0 ? burstCapacity : requestsPerMinute;
        long now = nanoClock.getAsLong();
        evictIfNeeded(now);
        Bucket bucket = buckets.computeIfAbsent(scope + ":" + subject,
                key -> new Bucket(capacity, now));
        synchronized (bucket) {
            refill(bucket, requestsPerMinute, capacity, now);
            bucket.lastAccessNanos = now;
            if (bucket.tokens >= 1.0d) {
                bucket.tokens -= 1.0d;
                return true;
            }
            return false;
        }
    }

    public long retryAfterSeconds(String scope, String subject, int requestsPerMinute, int burstCapacity) {
        if (!properties.getRateLimit().isEnabled() || requestsPerMinute <= 0) {
            return 0;
        }
        Bucket bucket = buckets.get(scope + ":" + subject);
        if (bucket == null) {
            return 0;
        }
        synchronized (bucket) {
            double missing = Math.max(0.0d, 1.0d - bucket.tokens);
            double perSecond = requestsPerMinute / 60.0d;
            return Math.max(1L, (long) Math.ceil(missing / perSecond));
        }
    }

    private void refill(Bucket bucket, int requestsPerMinute, int capacity, long now) {
        long elapsed = Math.max(0L, now - bucket.lastRefillNanos);
        if (elapsed == 0L) {
            return;
        }
        double perNano = requestsPerMinute / 60.0d / 1_000_000_000.0d;
        bucket.tokens = Math.min(capacity, bucket.tokens + elapsed * perNano);
        bucket.lastRefillNanos = now;
    }

    private void evictIfNeeded(long now) {
        if (buckets.size() <= MAX_BUCKETS) {
            return;
        }
        buckets.entrySet().removeIf(entry -> now - entry.getValue().lastAccessNanos > IDLE_EVICT_NANOS);
    }

    private static final class Bucket {
        private double tokens;
        private long lastRefillNanos;
        private long lastAccessNanos;

        private Bucket(int capacity, long now) {
            this.tokens = capacity;
            this.lastRefillNanos = now;
            this.lastAccessNanos = now;
        }
    }
}
