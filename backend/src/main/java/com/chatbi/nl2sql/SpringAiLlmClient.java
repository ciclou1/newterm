package com.chatbi.nl2sql;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@ConditionalOnBean(OpenAiChatModel.class)
public class SpringAiLlmClient {

    private final OpenAiChatModel chatModel;

    public SpringAiLlmClient(OpenAiChatModel chatModel) {
        this.chatModel = chatModel;
    }

    public LlmResponse complete(String systemPrompt, String userPrompt) {
        long start = System.currentTimeMillis();
        Prompt prompt = new Prompt(List.of(new SystemMessage(systemPrompt), new UserMessage(userPrompt)));
        ChatResponse response = chatModel.call(prompt);
        if (response == null || response.getResult() == null) {
            throw new IllegalStateException("模型未返回内容");
        }
        String text = response.getResult().getOutput().getText();
        int promptTokens = 0;
        int completionTokens = 0;
        Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
        if (usage != null) {
            promptTokens = usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
            completionTokens = usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
        }
        String model = response.getMetadata() == null ? null : response.getMetadata().getModel();
        log.debug("LLM completion model={} promptTokens={} completionTokens={} elapsed={}ms",
                model, promptTokens, completionTokens, System.currentTimeMillis() - start);
        return new LlmResponse(text == null ? "" : text, promptTokens, completionTokens, model,
                System.currentTimeMillis() - start);
    }
}
