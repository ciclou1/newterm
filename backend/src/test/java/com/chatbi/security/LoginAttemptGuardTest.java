package com.chatbi.security;

import com.chatbi.common.ChatBiException;
import com.chatbi.config.ChatBiProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoginAttemptGuardTest {

    private final AtomicLong now = new AtomicLong(1_000_000L);
    private ChatBiProperties properties;
    private LoginAttemptGuard guard;

    @BeforeEach
    void setUp() {
        properties = new ChatBiProperties();
        properties.getRateLimit().setLoginFailureThreshold(3);
        properties.getRateLimit().setLoginFailureWindowSeconds(60);
        properties.getRateLimit().setLoginLockSeconds(30);
        guard = new LoginAttemptGuard(properties, now::get);
    }

    @Test
    void locksAfterTheConfiguredNumberOfFailures() {
        guard.onFailure("alice");
        guard.onFailure("ALICE");
        assertThatCode(() -> guard.checkAllowed("alice")).doesNotThrowAnyException();

        guard.onFailure("alice");

        assertThatThrownBy(() -> guard.checkAllowed("alice"))
                .isInstanceOfSatisfying(ChatBiException.class, error -> {
                    org.assertj.core.api.Assertions.assertThat(error.getCode())
                            .isEqualTo("LOGIN_TEMPORARILY_LOCKED");
                    org.assertj.core.api.Assertions.assertThat(error.getStatus().value()).isEqualTo(429);
                });
        assertThatCode(() -> guard.checkAllowed("bob")).doesNotThrowAnyException();
    }

    @Test
    void unlocksAfterTheCoolingOffPeriod() {
        guard.onFailure("alice");
        guard.onFailure("alice");
        guard.onFailure("alice");

        now.addAndGet(31_000L);

        assertThatCode(() -> guard.checkAllowed("alice")).doesNotThrowAnyException();
    }

    @Test
    void successesClearTheCounter() {
        guard.onFailure("alice");
        guard.onFailure("alice");
        guard.onSuccess("alice");
        guard.onFailure("alice");

        assertThatCode(() -> guard.checkAllowed("alice")).doesNotThrowAnyException();
    }

    @Test
    void failuresOutsideTheWindowDoNotAccumulate() {
        guard.onFailure("alice");
        guard.onFailure("alice");
        now.addAndGet(61_000L);
        guard.onFailure("alice");

        assertThatCode(() -> guard.checkAllowed("alice")).doesNotThrowAnyException();
    }

    @Test
    void ignoresBlankUsernamesAndDisabledThreshold() {
        guard.onFailure(null);
        guard.onFailure("  ");
        assertThatCode(() -> guard.checkAllowed(null)).doesNotThrowAnyException();

        properties.getRateLimit().setLoginFailureThreshold(0);
        for (int i = 0; i < 10; i++) {
            guard.onFailure("bob");
        }
        assertThatCode(() -> guard.checkAllowed("bob")).doesNotThrowAnyException();
    }
}
