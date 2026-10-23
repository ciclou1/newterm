package com.chatbi.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName("query_feedback")
public class QueryFeedback {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long auditId;
    private String userId;
    private Integer rating;
    private String comment;
    private OffsetDateTime createdAt;
}
