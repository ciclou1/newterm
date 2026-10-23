package com.chatbi.service;

import com.chatbi.common.ChatBiException;
import com.chatbi.domain.entity.AppUser;
import com.chatbi.domain.entity.QueryAudit;
import com.chatbi.mapper.QueryAuditMapper;
import com.chatbi.nl2sql.AgentResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;

@Slf4j
@Service
public class AuditService {

    private final QueryAuditMapper auditMapper;
    private final ObjectMapper objectMapper;

    public AuditService(QueryAuditMapper auditMapper, ObjectMapper objectMapper) {
        this.auditMapper = auditMapper;
        this.objectMapper = objectMapper;
    }

    public Long record(String requestId, java.util.UUID conversationId, AppUser user, AgentResult result) {
        QueryAudit audit = new QueryAudit();
        audit.setRequestId(requestId);
        audit.setConversationId(conversationId);
        audit.setUserId(user.getUsername());
        audit.setQuestion(result.question());
        audit.setGeneratedSql(trim(result.generatedSql(), 8000));
        audit.setFinalSql(trim(result.finalSql(), 8000));
        audit.setGeneratedBy(result.mode());
        audit.setStatus(result.status());
        audit.setStage(result.failure() == null ? null : result.failure().stage());
        audit.setErrorMessage(trim(result.failure() == null ? null : result.failure().message(), 2000));
        audit.setAttempts(result.usage() == null ? 0 : result.usage().attempts());
        audit.setRowCount(result.result() == null ? 0 : result.result().rowCount());
        audit.setElapsedMs(result.performance() == null ? 0 : result.performance().totalMs());
        audit.setPromptTokens(result.usage() == null ? 0 : result.usage().promptTokens());
        audit.setCompletionTokens(result.usage() == null ? 0 : result.usage().completionTokens());
        audit.setCostUsd(result.usage() == null || result.usage().costUsd() == null
                ? java.math.BigDecimal.ZERO : result.usage().costUsd());
        audit.setSchemaHits(toJson(result.schema() == null ? null : result.schema().hits()));
        audit.setCreatedAt(OffsetDateTime.now());
        try {
            auditMapper.insert(audit);
            return audit.getId();
        } catch (Exception e) {
            // Fail closed: an untraceable analytical query must not be
            // presented to the user as a successful answer.
            log.error("Audit insert failed, refusing to return the query result", e);
            throw new ChatBiException("AUDIT_UNAVAILABLE",
                    "审计写入失败，查询结果未返回；请稍后重试或联系管理员",
                    HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "[]";
        }
    }

    private String trim(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
