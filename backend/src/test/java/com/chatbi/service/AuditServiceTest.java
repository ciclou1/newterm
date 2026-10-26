package com.chatbi.service;

import com.chatbi.common.ChatBiException;
import com.chatbi.domain.entity.AppUser;
import com.chatbi.domain.entity.QueryAudit;
import com.chatbi.mapper.QueryAuditMapper;
import com.chatbi.metrics.ChatBiMetrics;
import com.chatbi.nl2sql.AgentResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuditServiceTest {

    private final QueryAuditMapper auditMapper = mock(QueryAuditMapper.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final AuditService auditService =
            new AuditService(auditMapper, new ObjectMapper(), new ChatBiMetrics(meterRegistry));

    @Test
    void returnsTheGeneratedAuditIdOnSuccess() {
        when(auditMapper.insert(any(QueryAudit.class))).thenAnswer(invocation -> {
            QueryAudit audit = invocation.getArgument(0);
            audit.setId(42L);
            return 1;
        });

        Long id = auditService.record("req-1", null, user(), answer());

        assertThat(id).isEqualTo(42L);
    }

    @Test
    void failsClosedWhenTheAuditWriteFails() {
        when(auditMapper.insert(any(QueryAudit.class)))
                .thenThrow(new RuntimeException("audit database unavailable"));

        assertThatThrownBy(() -> auditService.record("req-2", null, user(), answer()))
                .isInstanceOf(ChatBiException.class)
                .satisfies(error -> {
                    ChatBiException exception = (ChatBiException) error;
                    assertThat(exception.getCode()).isEqualTo("AUDIT_UNAVAILABLE");
                    assertThat(exception.getStatus().is5xxServerError()).isTrue();
                });
        assertThat(meterRegistry.counter("chatbi.audit.fail.closed").count()).isEqualTo(1.0);
    }

    private AppUser user() {
        AppUser user = new AppUser();
        user.setUsername("lisi");
        user.setIsAdmin(true);
        user.setEnabled(true);
        return user;
    }

    private AgentResult answer() {
        return new AgentResult("OK", "今年销售额", "AI", "SELECT 1", "SELECT 1", "说明", 0.9,
                null, AgentResult.GuardInfo.empty(),
                new AgentResult.QueryResult(List.of(), List.of(), false, 0),
                AgentResult.Usage.empty(),
                new AgentResult.Performance(0, 0, 0, 0, 1, null, List.of()),
                AgentResult.SchemaInfo.empty(), new AgentResult.Failure(null, null, null),
                List.of(), false, null);
    }
}
