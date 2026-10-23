package com.chatbi.nl2sql;

import com.chatbi.domain.entity.AppUser;
import com.chatbi.linking.SchemaContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class AiSqlGenerator {

    private final ObjectProvider<SpringAiLlmClient> llmClient;
    private final PromptBuilder promptBuilder;
    private final ObjectMapper objectMapper;

    public AiSqlGenerator(ObjectProvider<SpringAiLlmClient> llmClient,
                          PromptBuilder promptBuilder,
                          ObjectMapper objectMapper) {
        this.llmClient = llmClient;
        this.promptBuilder = promptBuilder;
        this.objectMapper = objectMapper;
    }

    public boolean available() {
        return llmClient.getIfAvailable() != null;
    }

    public SqlGeneration generate(String question, SchemaContext schema, AppUser user,
                                  List<ConversationTurn> history, String previousSql, String previousError) {
        SpringAiLlmClient client = llmClient.getIfAvailable();
        if (client == null) {
            return SqlGeneration.error("AI 未启用", "AI", 0, 0, null);
        }
        String system = promptBuilder.systemPrompt(schema, user);
        String userPrompt = promptBuilder.userPrompt(question, history, previousSql, previousError);
        try {
            LlmResponse response = client.complete(system, userPrompt);
            return parse(response);
        } catch (Exception e) {
            log.warn("LLM call failed: {}", e.getMessage());
            return SqlGeneration.error("模型调用失败：" + shortMessage(e), "AI", 0, 0, null);
        }
    }

    private SqlGeneration parse(LlmResponse response) {
        String raw = response.text() == null ? "" : response.text().trim();
        String json = extractJson(raw);
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode clarificationNode = root.get("clarification");
            if (clarificationNode != null && clarificationNode.isObject()) {
                List<SqlGeneration.Clarification.Option> options = new ArrayList<>();
                JsonNode optionNodes = clarificationNode.get("options");
                if (optionNodes != null && optionNodes.isArray()) {
                    for (JsonNode option : optionNodes) {
                        options.add(new SqlGeneration.Clarification.Option(
                                option.path("label").asText("选项"),
                                option.path("question").asText("")));
                    }
                }
                String clarifyQuestion = clarificationNode.path("question").asText("请补充查询口径");
                return new SqlGeneration(null, null, 0,
                        new SqlGeneration.Clarification(clarifyQuestion, options), "AI",
                        response.promptTokens(), response.completionTokens(), response.model(), null);
            }
            String sql = text(root, "sql");
            if (sql == null || sql.isBlank()) {
                return SqlGeneration.error("模型未返回 sql 字段", "AI",
                        response.promptTokens(), response.completionTokens(), response.model());
            }
            sql = stripFence(sql).trim();
            if (sql.endsWith(";")) {
                sql = sql.substring(0, sql.length() - 1);
            }
            String explanation = text(root, "explanation");
            double confidence = root.path("confidence").asDouble(0.7);
            return new SqlGeneration(sql, explanation, confidence, null, "AI",
                    response.promptTokens(), response.completionTokens(), response.model(), null);
        } catch (Exception e) {
            String fallbackSql = extractBareSql(raw);
            if (fallbackSql != null) {
                return new SqlGeneration(fallbackSql, "模型以纯文本返回 SQL", 0.5, null, "AI",
                        response.promptTokens(), response.completionTokens(), response.model(), null);
            }
            return SqlGeneration.error("模型输出不是合法 JSON：" + shortMessage(e), "AI",
                    response.promptTokens(), response.completionTokens(), response.model());
        }
    }

    private String extractJson(String raw) {
        String cleaned = stripFence(raw);
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return cleaned.substring(start, end + 1);
        }
        return cleaned;
    }

    private String stripFence(String raw) {
        String result = raw.trim();
        if (result.startsWith("```")) {
            int firstNewline = result.indexOf('\n');
            if (firstNewline > 0) {
                result = result.substring(firstNewline + 1);
            }
            if (result.endsWith("```")) {
                result = result.substring(0, result.length() - 3);
            }
        }
        return result.trim();
    }

    private String extractBareSql(String raw) {
        String lower = raw.toLowerCase();
        int start = lower.indexOf("select ");
        if (start < 0) {
            return null;
        }
        String candidate = raw.substring(start).trim();
        int fence = candidate.indexOf("```");
        if (fence >= 0) {
            candidate = candidate.substring(0, fence).trim();
        }
        if (candidate.endsWith(";")) {
            candidate = candidate.substring(0, candidate.length() - 1);
        }
        return candidate.isBlank() ? null : candidate;
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private String shortMessage(Exception e) {
        String message = e.getMessage();
        if (message == null) {
            return e.getClass().getSimpleName();
        }
        int newline = message.indexOf('\n');
        return newline > 0 ? message.substring(0, newline) : message;
    }
}
