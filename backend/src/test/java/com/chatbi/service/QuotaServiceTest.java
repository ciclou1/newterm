package com.chatbi.service;

import com.chatbi.common.ChatBiException;
import com.chatbi.config.ChatBiProperties;
import com.chatbi.metrics.ChatBiMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class QuotaServiceTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final ChatBiMetrics metrics = mock(ChatBiMetrics.class);
    private final ChatBiProperties properties = new ChatBiProperties();
    private QuotaService quotaService;

    @BeforeEach
    void setUp() {
        properties.getQuota().setDailyTokenLimit(1000L);
        properties.getQuota().setDailyCostLimitUsd(new BigDecimal("2.00"));
        quotaService = new QuotaService(jdbcTemplate, properties, metrics);
    }

    private void usage(long tokens, String cost) {
        when(jdbcTemplate.queryForMap(anyString(), eq("alice")))
                .thenReturn(Map.of("tokens", tokens, "cost_usd", new BigDecimal(cost)));
    }

    @Test
    void allowsUsageUnderBothLimits() {
        usage(999, "1.99");

        assertThatCode(() -> quotaService.check("alice")).doesNotThrowAnyException();
    }

    @Test
    void rejectsWhenTokenLimitReached() {
        usage(1000, "0.00");

        assertThatThrownBy(() -> quotaService.check("alice"))
                .isInstanceOfSatisfying(ChatBiException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("DAILY_TOKEN_QUOTA_EXCEEDED");
                    assertThat(e.getStatus().value()).isEqualTo(429);
                });
    }

    @Test
    void rejectsWhenCostLimitReached() {
        usage(10, "2.00");

        assertThatThrownBy(() -> quotaService.check("alice"))
                .isInstanceOfSatisfying(ChatBiException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("DAILY_COST_QUOTA_EXCEEDED");
                    assertThat(e.getStatus().value()).isEqualTo(429);
                });
    }

    @Test
    void failsClosedWhenDatabaseIsUnavailable() {
        when(jdbcTemplate.queryForMap(anyString(), eq("alice")))
                .thenThrow(new DataAccessResourceFailureException("database down"));

        assertThatThrownBy(() -> quotaService.check("alice"))
                .isInstanceOfSatisfying(ChatBiException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("QUOTA_UNAVAILABLE");
                    assertThat(e.getStatus().value()).isEqualTo(503);
                });
    }

    @Test
    void skipsQueryWhenBothLimitsDisabled() {
        properties.getQuota().setDailyTokenLimit(0L);
        properties.getQuota().setDailyCostLimitUsd(BigDecimal.ZERO);

        assertThatCode(() -> quotaService.check("alice")).doesNotThrowAnyException();
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void parsesNumericCostValues() {
        when(jdbcTemplate.queryForMap(anyString(), eq("alice")))
                .thenReturn(Map.of("tokens", 5L, "cost_usd", 0.25d));

        assertThat(quotaService.usage("alice").costUsd()).isEqualByComparingTo("0.25");
    }
}
