package com.chatbi.nl2sql;

import com.chatbi.common.SecretRedactor;
import com.chatbi.config.ChatBiProperties;
import com.chatbi.domain.entity.AppUser;
import com.chatbi.linking.SchemaContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class AiSqlGenerator {

    private final ObjectProvider<SpringAiLlmClient> llmClient;
    private final PromptBuilder promptBuilder;
    private final ObjectMapper objectMapper;
    private final ChatBiProperties properties;

    public AiSqlGenerator(ObjectProvider<SpringAiLlmClient> llmClient,
                          PromptBuilder promptBuilder,
                          ObjectMapper objectMapper,
                          ChatBiProperties properties) {
        this.llmClient = llmClient;
        this.promptBuilder = promptBuilder;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /**
     * Fail closed at startup. Before this check, a mis-ordered
     * {@code @ConditionalOnBean} could leave AI enabled with no client bean and
     * the application would silently answer every question with the rule
     * engine while the operator believed the LLM was live.
     */
    @PostConstruct
    void verifyConfiguration() {
        boolean configured = properties.getAi().isEnabled();
        boolean wired = llmClient.getIfAvailable() != null;
        if (configured && !wired) {
            throw new IllegalStateException(
                    "chatbi.ai.enabled=true 但未装配 LLM 客户端。请检查 AI 配置（API Key / baseUrl），"
                            + "或显式设置 CHATBI_AI_ENABLED=false 使用离线演示模式。");
        }
        if (configured) {
            log.info("AI mode=LLM model={} baseUrl={}", properties.getAi().getModel(),
                    properties.getAi().getBaseUrl());
        } else {
            log.info("AI mode=DEMO (chatbi.ai.enabled=false); the deterministic rule engine is the primary planner");
        }
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
        PromptBuilder.BuiltPrompt prompt = promptBuilder.build(schema, user, question, history,
                previousSql, previousError);
        try {
            LlmResponse response = client.complete(prompt.system(), prompt.user());
            return LlmOutputParser.parse(response.text(), response, objectMapper);
        } catch (Exception e) {
            String message = e instanceof SpringAiLlmClient.LlmTimeoutException
                    ? shortMessage(e)
                    : "模型调用失败：" + shortMessage(e);
            log.warn("LLM call failed: {}", shortMessage(e));
            return SqlGeneration.error(message, "AI", 0, 0, null);
        }
    }

    private String shortMessage(Throwable e) {
        String message = e.getMessage();
        if (message == null) {
            return e.getClass().getSimpleName();
        }
        int newline = message.indexOf('\n');
        String single = newline > 0 ? message.substring(0, newline) : message;
        String redacted = SecretRedactor.redact(single);
        return redacted.length() > 300 ? redacted.substring(0, 300) + "…" : redacted;
    }
}
