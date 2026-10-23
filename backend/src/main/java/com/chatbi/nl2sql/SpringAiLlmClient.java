package com.chatbi.nl2sql;

import com.chatbi.config.ChatBiProperties.Ai;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * OpenAI-compatible chat client with production safeguards:
 * <ul>
 *   <li>single, explicit place where the HTTP call happens, so retry/timeout
 *       behaviour is visible and testable;</li>
 *   <li>optional JSON-object response format with a one-time graceful fallback
 *       when the gateway rejects {@code response_format};</li>
 *   <li>bounded retries with exponential backoff for 429/5xx/timeouts;</li>
 *   <li>usage-based token accounting and Micrometer metrics;</li>
 *   <li>the answer is always the message {@code content}. DeepSeek-style
 *       {@code reasoning_content} is never treated as the SQL payload.</li>
 * </ul>
 */
@Slf4j
public class SpringAiLlmClient {

    private final OpenAiChatModel chatModel;
    private final Ai ai;
    private final MeterRegistry meterRegistry;
    private final OpenAiChatOptions baseOptions;

    private final AtomicLong calls = new AtomicLong();
    private final AtomicLong successes = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private final AtomicLong retries = new AtomicLong();
    private final AtomicLong jsonFallbacks = new AtomicLong();
    private volatile boolean jsonModeSupported = true;
    private volatile String lastModel;
    private volatile String lastError;
    private volatile long lastElapsedMs = -1;
    private volatile long lastPromptTokens = -1;
    private volatile long lastCompletionTokens = -1;

    public SpringAiLlmClient(OpenAiChatModel chatModel, Ai ai, MeterRegistry meterRegistry) {
        this.chatModel = chatModel;
        this.ai = ai;
        this.meterRegistry = meterRegistry;
        this.baseOptions = OpenAiChatOptions.builder()
                .model(ai.getModel())
                .temperature(ai.getTemperature())
                .maxTokens(ai.getMaxTokens())
                .build();
        this.jsonModeSupported = ai.isJsonMode();
    }

    public LlmResponse complete(String systemPrompt, String userPrompt) {
        long start = System.currentTimeMillis();
        int maxCalls = Math.max(1, ai.getMaxRetries() + 1) + (ai.isJsonMode() ? 1 : 0);
        int transientRetries = 0;
        boolean jsonRequested = ai.isJsonMode() && jsonModeSupported;
        RuntimeException last = null;

        for (int call = 1; call <= maxCalls; call++) {
            calls.incrementAndGet();
            try {
                ChatResponse response = call(systemPrompt, userPrompt, jsonRequested);
                LlmResponse result = toResponse(response, System.currentTimeMillis() - start);
                recordSuccess(result);
                return result;
            } catch (RuntimeException e) {
                last = e;
                if (jsonRequested && isFormatRejection(e)) {
                    jsonRequested = false;
                    jsonModeSupported = false;
                    jsonFallbacks.incrementAndGet();
                    log.warn("Gateway rejected JSON response_format; retrying without it (model={}): {}",
                            ai.getModel(), shortMessage(e));
                    continue;
                }
                if (transientRetries >= ai.getMaxRetries() || !isRetryable(e)) {
                    break;
                }
                transientRetries++;
                retries.incrementAndGet();
                long backoff = backoffMillis(transientRetries);
                log.warn("LLM call failed (attempt {}/{}), retrying in {}ms: {}",
                        call, maxCalls, backoff, shortMessage(e));
                sleep(backoff);
            }
        }

        failures.incrementAndGet();
        lastError = last == null ? "unknown" : shortMessage(last);
        meterRegistry.counter("chatbi.llm.calls", "model", safeModel(), "outcome", "error").increment();
        throw last == null ? new IllegalStateException("LLM call failed") : last;
    }

    private ChatResponse call(String systemPrompt, String userPrompt, boolean jsonRequested) {
        OpenAiChatOptions options = OpenAiChatOptions.fromOptions(baseOptions);
        if (jsonRequested) {
            options.setResponseFormat(ResponseFormat.builder()
                    .type(ResponseFormat.Type.JSON_OBJECT)
                    .build());
        }
        Prompt prompt = new Prompt(List.of(new SystemMessage(systemPrompt), new UserMessage(userPrompt)),
                options);
        return chatModel.call(prompt);
    }

    private LlmResponse toResponse(ChatResponse response, long elapsedMs) {
        if (response == null || response.getResult() == null
                || response.getResult().getOutput() == null) {
            throw new IllegalStateException("模型未返回内容");
        }
        String text = contentOf(response);
        Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
        int promptTokens = usage == null || usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
        int completionTokens = usage == null || usage.getCompletionTokens() == null
                ? 0 : usage.getCompletionTokens();
        String model = response.getMetadata() == null ? null : response.getMetadata().getModel();
        log.debug("LLM completion model={} promptTokens={} completionTokens={} elapsed={}ms",
                model, promptTokens, completionTokens, elapsedMs);
        return new LlmResponse(text, promptTokens, completionTokens,
                model == null || model.isBlank() ? ai.getModel() : model, elapsedMs);
    }

    /**
     * The answer is the assistant message {@code content}. Providers such as
     * DeepSeek also return {@code reasoning_content}; Spring AI keeps that in
     * message metadata and it must never leak into SQL parsing.
     */
    static String contentOf(ChatResponse response) {
        String text = response.getResult().getOutput().getText();
        return text == null ? "" : text;
    }

    private void recordSuccess(LlmResponse result) {
        successes.incrementAndGet();
        lastModel = result.model();
        lastElapsedMs = result.elapsedMs();
        lastPromptTokens = result.promptTokens();
        lastCompletionTokens = result.completionTokens();
        lastError = null;
        meterRegistry.counter("chatbi.llm.calls", "model", safeModel(), "outcome", "success").increment();
        Timer.builder("chatbi.llm.request.duration")
                .tag("model", safeModel())
                .tag("outcome", "success")
                .register(meterRegistry)
                .record(Duration.ofMillis(Math.max(0, result.elapsedMs())));
        meterRegistry.counter("chatbi.llm.tokens", "model", safeModel(), "kind", "prompt")
                .increment(result.promptTokens());
        meterRegistry.counter("chatbi.llm.tokens", "model", safeModel(), "kind", "completion")
                .increment(result.completionTokens());
    }

    private boolean isRetryable(RuntimeException e) {
        if (e instanceof TransientAiException) {
            return true;
        }
        if (e instanceof NonTransientAiException) {
            return hasRetryableStatus(e.getMessage());
        }
        if (e instanceof RestClientResponseException responseException) {
            return retryableStatus(responseException.getStatusCode());
        }
        if (e instanceof ResourceAccessException) {
            return true;
        }
        for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof SocketTimeoutException) {
                return true;
            }
            if (cause instanceof RestClientResponseException responseException) {
                return retryableStatus(responseException.getStatusCode());
            }
        }
        return false;
    }

    /**
     * A JSON-mode rejection is a request-format problem, not a transient
     * failure: retry once without response_format instead of surfacing it.
     */
    private boolean isFormatRejection(RuntimeException e) {
        RestClientResponseException responseException = findResponseException(e);
        if (responseException != null) {
            int status = responseException.getStatusCode().value();
            return status == 400 || status == 404 || status == 415 || status == 422;
        }
        String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        return message.contains("response_format") || message.contains("json_object");
    }

    private RestClientResponseException findResponseException(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof RestClientResponseException responseException) {
                return responseException;
            }
        }
        return null;
    }

    private boolean retryableStatus(HttpStatusCode status) {
        return status.value() == 429 || status.is5xxServerError();
    }

    private boolean hasRetryableStatus(String message) {
        if (message == null) {
            return false;
        }
        return message.contains("429") || message.contains("500") || message.contains("502")
                || message.contains("503") || message.contains("504") || message.contains("timeout");
    }

    private long backoffMillis(int retry) {
        long base = Math.max(1, ai.getRetryBackoffMs());
        long exponential = base * (1L << Math.min(5, Math.max(0, retry - 1)));
        long jitter = (long) (exponential * 0.2 * Math.random());
        return exponential + jitter;
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("LLM retry interrupted", e);
        }
    }

    private String safeModel() {
        return lastModel == null || lastModel.isBlank() ? ai.getModel() : lastModel;
    }

    private String shortMessage(Throwable e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return e.getClass().getSimpleName();
        }
        int newline = message.indexOf('\n');
        String single = newline > 0 ? message.substring(0, newline) : message;
        return single.length() > 300 ? single.substring(0, 300) + "…" : single;
    }

    /** Snapshot exposed through the AI health indicator; never contains the key. */
    public Stats stats() {
        return new Stats(true, ai.getModel(), ai.getBaseUrl(), jsonModeSupported,
                calls.get(), successes.get(), failures.get(), retries.get(), jsonFallbacks.get(),
                lastModel, lastError, lastElapsedMs, lastPromptTokens, lastCompletionTokens);
    }

    public record Stats(boolean available, String configuredModel, String baseUrl,
                        boolean jsonModeSupported, long calls, long successes, long failures,
                        long retries, long jsonFallbacks, String lastModel, String lastError,
                        long lastElapsedMs, long lastPromptTokens, long lastCompletionTokens) {
    }
}
