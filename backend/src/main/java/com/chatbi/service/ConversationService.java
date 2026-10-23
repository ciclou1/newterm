package com.chatbi.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chatbi.common.ChatBiException;
import com.chatbi.domain.entity.AppUser;
import com.chatbi.domain.entity.Conversation;
import com.chatbi.domain.entity.ConversationMessage;
import com.chatbi.mapper.ConversationMapper;
import com.chatbi.mapper.ConversationMessageMapper;
import com.chatbi.nl2sql.ConversationTurn;
import com.chatbi.presentation.QueryResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
public class ConversationService {

    private final ConversationMapper conversationMapper;
    private final ConversationMessageMapper messageMapper;
    private final ObjectMapper objectMapper;

    public ConversationService(ConversationMapper conversationMapper,
                               ConversationMessageMapper messageMapper,
                               ObjectMapper objectMapper) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ConversationContext begin(UUID conversationId, AppUser user, String question) {
        Conversation conversation;
        if (conversationId == null) {
            conversation = new Conversation();
            conversation.setId(UUID.randomUUID());
            conversation.setUserId(user.getUsername());
            conversation.setTitle(title(question));
            conversation.setCreatedAt(OffsetDateTime.now());
            conversation.setUpdatedAt(OffsetDateTime.now());
            conversationMapper.insert(conversation);
        } else {
            conversation = conversationMapper.selectById(conversationId);
            if (conversation == null) {
                throw ChatBiException.notFound("会话不存在");
            }
            if (!conversation.getUserId().equals(user.getUsername())) {
                throw ChatBiException.forbidden("CONVERSATION_FORBIDDEN", "无权访问该会话");
            }
            conversation.setUpdatedAt(OffsetDateTime.now());
            conversationMapper.updateById(conversation);
        }

        List<ConversationTurn> history = history(conversation.getId());
        ConversationMessage userMessage = new ConversationMessage();
        userMessage.setConversationId(conversation.getId());
        userMessage.setRole("user");
        userMessage.setContent(question);
        userMessage.setCreatedAt(OffsetDateTime.now());
        messageMapper.insert(userMessage);
        return new ConversationContext(conversation, history);
    }

    @Transactional
    public void complete(Conversation conversation, QueryResponse response) {
        ConversationMessage message = new ConversationMessage();
        message.setConversationId(conversation.getId());
        message.setRole("assistant");
        message.setContent(response.answer() == null ? "" : response.answer());
        message.setSqlText(response.result() == null ? null : response.result().finalSql());
        message.setPayload(toJson(response));
        message.setCreatedAt(OffsetDateTime.now());
        messageMapper.insert(message);

        conversation.setUpdatedAt(OffsetDateTime.now());
        conversationMapper.updateById(conversation);
    }

    public List<Conversation> list(AppUser user) {
        return conversationMapper.selectList(new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getUserId, user.getUsername())
                .orderByDesc(Conversation::getUpdatedAt)
                .last("LIMIT 50"));
    }

    public ConversationDetail detail(UUID id, AppUser user) {
        Conversation conversation = conversationMapper.selectById(id);
        if (conversation == null) {
            throw ChatBiException.notFound("会话不存在");
        }
        if (!conversation.getUserId().equals(user.getUsername())) {
            throw ChatBiException.forbidden("CONVERSATION_FORBIDDEN", "无权访问该会话");
        }
        List<ConversationMessage> messages = messageMapper.selectList(
                new LambdaQueryWrapper<ConversationMessage>()
                        .eq(ConversationMessage::getConversationId, id)
                        .orderByAsc(ConversationMessage::getId));
        List<MessageView> views = messages.stream()
                .map(m -> new MessageView(m.getId(), m.getRole(), m.getContent(), m.getSqlText(),
                        m.getPayload(), m.getCreatedAt()))
                .toList();
        return new ConversationDetail(conversation, views);
    }

    @Transactional
    public void delete(UUID id, AppUser user) {
        Conversation conversation = conversationMapper.selectById(id);
        if (conversation == null) {
            return;
        }
        if (!conversation.getUserId().equals(user.getUsername())) {
            throw ChatBiException.forbidden("CONVERSATION_FORBIDDEN", "无权删除该会话");
        }
        conversationMapper.deleteById(id);
    }

    private List<ConversationTurn> history(UUID conversationId) {
        List<ConversationMessage> messages = messageMapper.selectList(
                new LambdaQueryWrapper<ConversationMessage>()
                        .eq(ConversationMessage::getConversationId, conversationId)
                        .orderByDesc(ConversationMessage::getId)
                        .last("LIMIT 8"));
        Collections.reverse(messages);
        List<ConversationTurn> turns = new ArrayList<>();
        String pendingQuestion = null;
        for (ConversationMessage message : messages) {
            if ("user".equals(message.getRole())) {
                pendingQuestion = message.getContent();
            } else if ("assistant".equals(message.getRole()) && pendingQuestion != null) {
                turns.add(new ConversationTurn(pendingQuestion, message.getSqlText(), message.getContent()));
                pendingQuestion = null;
            }
        }
        return turns;
    }

    private String title(String question) {
        String value = question == null ? "新会话" : question.trim();
        return value.length() <= 40 ? value : value.substring(0, 40) + "…";
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("Failed to serialize message payload: {}", e.getMessage());
            return null;
        }
    }

    public record ConversationContext(Conversation conversation, List<ConversationTurn> history) {
    }

    public record ConversationDetail(Conversation conversation, List<MessageView> messages) {
    }

    public record MessageView(Long id, String role, String content, String sql, String payload,
                              OffsetDateTime createdAt) {
    }
}
