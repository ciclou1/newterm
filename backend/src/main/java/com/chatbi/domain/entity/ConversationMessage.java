package com.chatbi.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@TableName("conversation_message")
public class ConversationMessage {
    @TableId(type = IdType.AUTO)
    private Long id;
    private UUID conversationId;
    private String role;
    private String content;
    private String sqlText;
    private String payload;
    private OffsetDateTime createdAt;
}
