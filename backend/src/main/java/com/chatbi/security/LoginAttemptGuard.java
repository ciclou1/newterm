package com.chatbi.security;

import com.chatbi.common.ChatBiException;
import com.chatbi.config.ChatBiProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Per-username failed-login accounting. IP-based token buckets are easy to
 * bypass (rotating proxies, spoofed headers), so repeated failures for the same
 * account lock that account for a cooling-off period even when the attacker
 * changes network path. Stored in memory: multi-replica deployments need a
 * shared store for this to hold globally.
 */
@Component
public class LoginAttemptGuard {

    private static final long EVICT_AFTER_MILLIS = 24 * 60 * 60 * 1000L;
    private static final int MAX_ENTRIES = 10_000;

    private final ChatBiProperties properties;
    private final LongSupplier clock;
    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();

    @Autowired
    public LoginAttemptGuard(ChatBiProperties properties) {
        this(properties, System::currentTimeMillis);
    }

    LoginAttemptGuard(ChatBiProperties properties, LongSupplier clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public void checkAllowed(String username) {
        String key = key(username);
        if (key == null) {
            return;
        }
        Attempt attempt = attempts.get(key);
        if (attempt == null) {
            return;
        }
        long now = clock.getAsLong();
        synchronized (attempt) {
            long waitMillis = attempt.lockedUntil - now;
            if (waitMillis > 0) {
                throw ChatBiException.tooManyRequests("LOGIN_TEMPORARILY_LOCKED",
                        "登录失败次数过多，请在 " + Math.max(1, (waitMillis + 999) / 1000)
                                + " 秒后重试");
            }
        }
    }

    public void onFailure(String username) {
        String key = key(username);
        ChatBiProperties.RateLimit config = properties.getRateLimit();
        if (key == null || config.getLoginFailureThreshold() <= 0) {
            return;
        }
        long now = clock.getAsLong();
        evictIfNeeded(now);
        attempts.compute(key, (ignored, current) -> {
            Attempt attempt = current == null ? new Attempt(now) : current;
            synchronized (attempt) {
                if (now - attempt.windowStartMillis > config.getLoginFailureWindowSeconds() * 1000L) {
                    attempt.failures = 0;
                    attempt.windowStartMillis = now;
                }
                attempt.failures++;
                attempt.lastFailureMillis = now;
                if (attempt.failures >= config.getLoginFailureThreshold()) {
                    attempt.lockedUntil = now + Math.max(1, config.getLoginLockSeconds()) * 1000L;
                    attempt.failures = 0;
                    attempt.windowStartMillis = now;
                }
                return attempt;
            }
        });
    }

    public void onSuccess(String username) {
        String key = key(username);
        if (key != null) {
            attempts.remove(key);
        }
    }

    private void evictIfNeeded(long now) {
        if (attempts.size() <= MAX_ENTRIES) {
            return;
        }
        attempts.entrySet().removeIf(entry ->
                now - entry.getValue().lastFailureMillis > EVICT_AFTER_MILLIS);
    }

    private static String key(String username) {
        if (username == null || username.isBlank()) {
            return null;
        }
        return username.trim().toLowerCase(Locale.ROOT);
    }

    private static final class Attempt {
        private int failures;
        private long windowStartMillis;
        private long lockedUntil;
        private long lastFailureMillis;

        private Attempt(long now) {
            this.windowStartMillis = now;
            this.lastFailureMillis = now;
        }
    }
}
