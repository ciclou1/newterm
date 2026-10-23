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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class QuotaServiceTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final ChatBiMetrics metrics = mock(ChatBiMetrics.class);
    private final QuotaReservationStore store = mock(QuotaReservationStore.class);
    private final ChatBiProperties properties = new ChatBiProperties();
    private QuotaService quotaService;

    @BeforeEach
    void setUp() {
        properties.getQuota().setDailyTokenLimit(1000L);
        properties.getQuota().setDailyCostLimitUsd(new BigDecimal("2.00"));
        properties.getQuota().setReserveTokens(100);
        properties.getQuota().setReserveCostUsd(new BigDecimal("0.10"));
        quotaService = new QuotaService(jdbcTemplate, properties, metrics, store);
    }

    private void usage(long tokens, String cost) {
        when(jdbcTemplate.queryForMap(anyString(), eq("alice")))
                .thenReturn(Map.of("tokens", tokens, "cost_usd", new BigDecimal(cost)));
    }

    private void reservation(QuotaReservationStore.Result result) {
        when(store.tryReserve(anyString(), anyLong(), anyLong(), anyLong(), anyLong(),
                anyLong(), anyLong())).thenReturn(result);
    }

    @Test
    void reservesPartOfTheRemainingBudget() {
        usage(500, "1.00");
        reservation(QuotaReservationStore.Result.acquired(100, 100_000, "20261023"));

        QuotaService.Reservation reservation = quotaService.reserve("alice");

        assertThat(reservation.active()).isTrue();
        assertThat(reservation.tokens()).isEqualTo(100);
        assertThat(reservation.costMicros()).isEqualTo(100_000);
        assertThat(reservation.day()).isEqualTo("20261023");
        verify(store).tryReserve("alice", 500, 1_000_000, 1000, 2_000_000, 100, 100_000);
    }

    @Test
    void rejectsWhenTheAtomicReservationSaysTokensAreExhausted() {
        usage(1000, "0.00");
        reservation(QuotaReservationStore.Result.rejected(
                QuotaReservationStore.Status.TOKEN_EXCEEDED));

        assertThatThrownBy(() -> quotaService.reserve("alice"))
                .isInstanceOfSatisfying(ChatBiException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("DAILY_TOKEN_QUOTA_EXCEEDED");
                    assertThat(e.getStatus().value()).isEqualTo(429);
                });
    }

    @Test
    void rejectsWhenTheAtomicReservationSaysCostIsExhausted() {
        usage(10, "2.00");
        reservation(QuotaReservationStore.Result.rejected(
                QuotaReservationStore.Status.COST_EXCEEDED));

        assertThatThrownBy(() -> quotaService.reserve("alice"))
                .isInstanceOfSatisfying(ChatBiException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("DAILY_COST_QUOTA_EXCEEDED");
                    assertThat(e.getStatus().value()).isEqualTo(429);
                });
    }

    @Test
    void failsClosedWhenDatabaseIsUnavailable() {
        when(jdbcTemplate.queryForMap(anyString(), eq("alice")))
                .thenThrow(new DataAccessResourceFailureException("database down"));

        assertThatThrownBy(() -> quotaService.reserve("alice"))
                .isInstanceOfSatisfying(ChatBiException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("QUOTA_UNAVAILABLE");
                    assertThat(e.getStatus().value()).isEqualTo(503);
                });
        verifyNoInteractions(store);
    }

    @Test
    void failsClosedWhenRedisIsUnavailable() {
        usage(0, "0.00");
        when(store.tryReserve(anyString(), anyLong(), anyLong(), anyLong(), anyLong(),
                anyLong(), anyLong())).thenThrow(
                new org.springframework.data.redis.RedisConnectionFailureException("redis down"));

        assertThatThrownBy(() -> quotaService.reserve("alice"))
                .isInstanceOfSatisfying(ChatBiException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("QUOTA_UNAVAILABLE");
                    assertThat(e.getStatus().value()).isEqualTo(503);
                });
    }

    @Test
    void skipsEverythingWhenBothLimitsAreDisabled() {
        properties.getQuota().setDailyTokenLimit(0L);
        properties.getQuota().setDailyCostLimitUsd(BigDecimal.ZERO);

        QuotaService.Reservation reservation = quotaService.reserve("alice");

        assertThat(reservation.active()).isFalse();
        verifyNoInteractions(jdbcTemplate, store);
        assertThatCode(() -> quotaService.release(reservation)).doesNotThrowAnyException();
        verifyNoInteractions(store);
    }

    @Test
    void releasesAnAcquiredReservationAndSwallowsRedisFailures() {
        QuotaService.Reservation reservation =
                new QuotaService.Reservation("alice", 100, 100_000, "20261023", true);
        org.mockito.Mockito.doThrow(new org.springframework.data.redis.RedisConnectionFailureException("down"))
                .when(store).release("alice", "20261023", 100, 100_000);

        assertThatCode(() -> quotaService.release(reservation)).doesNotThrowAnyException();
        verify(store).release("alice", "20261023", 100, 100_000);
    }

    @Test
    void parsesNumericCostValues() {
        when(jdbcTemplate.queryForMap(anyString(), eq("alice")))
                .thenReturn(Map.of("tokens", 5L, "cost_usd", 0.25d));

        assertThat(quotaService.usage("alice").costUsd()).isEqualByComparingTo("0.25");
    }
}
