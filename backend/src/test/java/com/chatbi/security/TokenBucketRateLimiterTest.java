package com.chatbi.security;

import com.chatbi.config.ChatBiProperties;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class TokenBucketRateLimiterTest {

    private static final long ONE_SECOND = 1_000_000_000L;

    private final AtomicLong clock = new AtomicLong(0L);
    private final ChatBiProperties properties = new ChatBiProperties();
    private final TokenBucketRateLimiter limiter =
            new TokenBucketRateLimiter(properties, clock::get);

    @Test
    void allowsCapacityThenRejects() {
        for (int i = 0; i < 10; i++) {
            assertThat(limiter.tryAcquire("chat", "user:alice", 10, 0)).isTrue();
        }
        assertThat(limiter.tryAcquire("chat", "user:alice", 10, 0)).isFalse();
    }

    @Test
    void refillsOverTime() {
        for (int i = 0; i < 10; i++) {
            assertThat(limiter.tryAcquire("chat", "user:alice", 10, 0)).isTrue();
        }
        assertThat(limiter.tryAcquire("chat", "user:alice", 10, 0)).isFalse();

        clock.addAndGet(6 * ONE_SECOND);

        assertThat(limiter.tryAcquire("chat", "user:alice", 10, 0)).isTrue();
        assertThat(limiter.tryAcquire("chat", "user:alice", 10, 0)).isFalse();
    }

    @Test
    void refillIsCappedAtCapacity() {
        limiter.tryAcquire("chat", "user:alice", 10, 0);
        clock.addAndGet(3600 * ONE_SECOND);

        for (int i = 0; i < 10; i++) {
            assertThat(limiter.tryAcquire("chat", "user:alice", 10, 0)).isTrue();
        }
        assertThat(limiter.tryAcquire("chat", "user:alice", 10, 0)).isFalse();
    }

    @Test
    void subjectsAndScopesAreIsolated() {
        for (int i = 0; i < 10; i++) {
            assertThat(limiter.tryAcquire("chat", "user:alice", 10, 0)).isTrue();
        }
        assertThat(limiter.tryAcquire("chat", "user:alice", 10, 0)).isFalse();
        assertThat(limiter.tryAcquire("chat", "user:bob", 10, 0)).isTrue();
        assertThat(limiter.tryAcquire("login", "user:alice", 10, 0)).isTrue();
    }

    @Test
    void burstCapacityOverridesRequestsPerMinute() {
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire("chat", "user:alice", 10, 3)).isTrue();
        }
        assertThat(limiter.tryAcquire("chat", "user:alice", 10, 3)).isFalse();
    }

    @Test
    void retryAfterIsPositiveWhenRejected() {
        for (int i = 0; i < 10; i++) {
            limiter.tryAcquire("chat", "user:alice", 10, 0);
        }

        assertThat(limiter.retryAfterSeconds("chat", "user:alice", 10, 0)).isEqualTo(6L);
        assertThat(limiter.retryAfterSeconds("chat", "user:nobody", 10, 0)).isZero();
    }

    @Test
    void disabledLimiterAlwaysAllows() {
        properties.getRateLimit().setEnabled(false);

        for (int i = 0; i < 100; i++) {
            assertThat(limiter.tryAcquire("chat", "user:alice", 1, 1)).isTrue();
        }
        assertThat(limiter.retryAfterSeconds("chat", "user:alice", 1, 1)).isZero();
    }
}
