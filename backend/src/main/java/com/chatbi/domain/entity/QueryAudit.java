package com.chatbi.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@TableName("query_audit")
public class QueryAudit {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String requestId;
    private UUID conversationId;
    private String userId;
    private String question;
    private String generatedSql;
    private String finalSql;
    private String generatedBy;
    private String status;
    private String stage;
    private String errorMessage;
    private Integer attempts;
    private Integer rowCount;
    private Long elapsedMs;
    private Integer promptTokens;
    private Integer completionTokens;
    private BigDecimal costUsd;
    private String schemaHits;
    private OffsetDateTime createdAt;
}
