package com.chatbi.nl2sql;

import com.chatbi.common.SecretRedactor;
import com.chatbi.config.ChatBiProperties.Ai;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * OpenAI-compatible chat client with production safeguards:
 * <ul>
 *   <li>single, explicit place where the HTTP call happens, so retry/timeout
 *       behaviour is visible and testable;</li>
 *   <li>one retry owner (this class) and one hard deadline per completion;
 *       cancelling the deadline future interrupts the underlying HTTP
 *       exchange;</li>
 *   <li>optional JSON-object response format with a grace-period fallback when
 *       the gateway explicitly rejects {@code response_format}; the capability
 *       is re-probed after a TTL instead of being disabled forever;</li>
 *   <li>bounded retries with exponential backoff for 429/5xx/timeouts, honouring
 *       {@code Retry-After} on 429;</li>
 *   <li>usage-based token accounting and Micrometer metrics whose {@code model}
 *       tag is the configured model, not provider response metadata;</li>
 *   <li>credential-shaped strings are redacted before errors reach health,
 *       audit or user-visible messages;</li>
 *   <li>the answer is always the message {@code content}. DeepSeek-style
 *       {@code reasoning_content} is never treated as the SQL payload.</li>
 * </ul>
 */
@Slf4j
public class SpringAiLlmClient implements AutoCloseable {

    /** Prefix of the user-visible error when the total deadline is exceeded. */
    public static final String AI_TIMEOUT = "AI_TIMEOUT";

    private final OpenAiChatModel chatModel;
    private final Ai ai;
    private final MeterRegistry meterRegistry;
    private final OpenAiChatOptions baseOptions;
    private final ExecutorService httpExecutor = Executors.newVirtualThreadPerTaskExecutor();

    private final AtomicLong calls = new AtomicLong();
    private final AtomicLong successes = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private final AtomicLong retries = new AtomicLong();
    private final AtomicLong jsonFallbacks = new AtomicLong();
    private final AtomicLong httpAttempts = new AtomicLong();
    /** Epoch millis until which JSON mode is skipped after a provider rejection. */
    private volatile long jsonModeBlockedUntilMs;
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
    }

    public LlmResponse complete(String systemPrompt, String userPrompt) {
        long start = System.currentTimeMillis();
        long deadlineAt = start + totalTimeoutMillis();
        int maxCalls = Math.max(1, ai.getMaxRetries() + 1) + (ai.isJsonMode() ? 1 : 0);
        int transientRetries = 0;
        boolean jsonRequested = jsonModeAvailable();
        RuntimeException last = null;

        for (int call = 1; call <= maxCalls; call++) {
            long remaining = deadlineAt - System.currentTimeMillis();
            if (remaining <= 0) {
                last = timeoutException(null);
                break;
            }
            calls.incrementAndGet();
            try {
                ChatResponse response = callWithDeadline(systemPrompt, userPrompt, jsonRequested, remaining);
                LlmResponse result = toResponse(response, System.currentTimeMillis() - start);
                if (jsonRequested) {
                    // A successful JSON call proves the capability again.
                    jsonModeBlockedUntilMs = 0L;
                }
                recordSuccess(result);
                return result;
            } catch (RuntimeException e) {
                last = e;
                if (jsonRequested && isFormatRejection(e)) {
                    jsonRequested = false;
                    jsonModeBlockedUntilMs = System.currentTimeMillis() + jsonModeProbeTtlMillis();
                    jsonFallbacks.incrementAndGet();
                    log.warn("Gateway rejected JSON response_format; retrying without it. "
                                    + "JSON mode will be re-probed in {}s (model={}): {}",
                            ai.getJsonModeProbeTtlSeconds(), ai.getModel(), shortMessage(e));
                    continue;
                }
                if (e instanceof LlmTimeoutException) {
                    break;
                }
                if (transientRetries >= ai.getMaxRetries() || !isRetryable(e)) {
                    break;
                }
                transientRetries++;
                retries.incrementAndGet();
                remaining = deadlineAt - System.currentTimeMillis();
                if (remaining <= 0) {
                    last = timeoutException(e);
                    break;
                }
                int currentRetry = transientRetries;
                long backoff = retryAfterMillis(e).orElseGet(() -> backoffMillis(currentRetry));
                backoff = Math.min(Math.max(0, backoff), remaining);
                log.warn("LLM call failed (attempt {}/{}), retrying in {}ms: {}",
                        call, maxCalls, backoff, shortMessage(e));
                if (!sleep(backoff)) {
                    last = timeoutException(e);
                    break;
                }
                if (System.currentTimeMillis() >= deadlineAt) {
                    last = timeoutException(e);
                    break;
                }
            }
        }

        failures.incrementAndGet();
        lastError = last == null ? timeoutException(null).getMessage() : shortMessage(last);
        meterRegistry.counter("chatbi.llm.calls", "model", ai.getModel(), "outcome", "error").increment();
        throw last == null ? timeoutException(null) : last;
    }

    /**
     * Runs one HTTP attempt under the remaining total budget. Cancelling with
     * {@code true} interrupts the worker; because AiConfig wires
     * {@code JdkClientHttpRequestFactory}, that interrupt aborts the in-flight
     * HTTP exchange rather than leaving the socket blocked.
     */
    private ChatResponse callWithDeadline(String systemPrompt, String userPrompt,
                                          boolean jsonRequested, long remainingMs) {
        boolean useJson = jsonRequested;
        Future<ChatResponse> future = httpExecutor.submit(() -> {
            httpAttempts.incrementAndGet();
            meterRegistry.counter("chatbi.llm.http.attempts", "model", ai.getModel()).increment();
            return call(systemPrompt, userPrompt, useJson);
        });
        try {
            return future.get(Math.max(1, remainingMs), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new LlmTimeoutException(AI_TIMEOUT + ": LLM 请求超过总时限 "
                    + ai.getTotalTimeoutSeconds() + "s", e);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new LlmTimeoutException(AI_TIMEOUT + ": LLM 调用被中断", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("LLM call failed", cause);
        }
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
        meterRegistry.counter("chatbi.llm.calls", "model", ai.getModel(), "outcome", "success").increment();
        Timer.builder("chatbi.llm.request.duration")
                .tag("model", ai.getModel())
                .tag("outcome", "success")
                .register(meterRegistry)
                .record(Duration.ofMillis(Math.max(0, result.elapsedMs())));
        meterRegistry.counter("chatbi.llm.tokens", "model", ai.getModel(), "kind", "prompt")
                .increment(result.promptTokens());
        meterRegistry.counter("chatbi.llm.tokens", "model", ai.getModel(), "kind", "completion")
                .increment(result.completionTokens());
    }

    private boolean isRetryable(RuntimeException e) {
        if (e instanceof LlmTimeoutException) {
            return false;
        }
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
     * failure. Only an explicit mention of {@code response_format} or
     * {@code json_object} counts: a generic 400 (context too long), 404 (model
     * missing) or 422 (invalid request) must surface as the real error instead
     * of triggering a pointless fallback and a ten-minute capability outage.
     */
    private boolean isFormatRejection(RuntimeException e) {
        RestClientResponseException responseException = findResponseException(e);
        if (responseException != null) {
            int status = responseException.getStatusCode().value();
            if (status != 400 && status != 404 && status != 415 && status != 422) {
                return false;
            }
            return mentionsResponseFormat(responseException.getResponseBodyAsString())
                    || mentionsResponseFormat(responseException.getMessage());
        }
        return mentionsResponseFormat(e.getMessage());
    }

    private boolean mentionsResponseFormat(String text) {
        if (text == null) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("response_format") || lower.contains("json_object");
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

    private OptionalLong retryAfterMillis(Throwable e) {
        RestClientResponseException responseException = findResponseException(e);
        if (responseException == null || responseException.getStatusCode().value() != 429) {
            return OptionalLong.empty();
        }
        HttpHeaders headers = responseException.getResponseHeaders();
        String value = headers == null ? null : headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (value == null || value.isBlank()) {
            return OptionalLong.empty();
        }
        String trimmed = value.trim();
        try {
            return OptionalLong.of(Math.max(0, Long.parseLong(trimmed)) * 1000L);
        } catch (NumberFormatException ignored) {
            // Fall through to the HTTP-date form.
        }
        try {
            ZonedDateTime at = ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME);
            long millis = Duration.between(ZonedDateTime.now(at.getZone()), at).toMillis();
            return OptionalLong.of(Math.max(0, millis));
        } catch (DateTimeParseException ignored) {
            return OptionalLong.empty();
        }
    }

    private long backoffMillis(int retry) {
        long base = Math.max(1, ai.getRetryBackoffMs());
        long exponential = base * (1L << Math.min(5, Math.max(0, retry - 1)));
        long jitter = (long) (exponential * 0.2 * Math.random());
        return exponential + jitter;
    }

    /** @return false when interrupted, true when the full sleep completed. */
    private boolean sleep(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private long totalTimeoutMillis() {
        return Math.max(1, ai.getTotalTimeoutSeconds()) * 1000L;
    }

    private long jsonModeProbeTtlMillis() {
        return Math.max(0, ai.getJsonModeProbeTtlSeconds()) * 1000L;
    }

    private boolean jsonModeAvailable() {
        return ai.isJsonMode() && System.currentTimeMillis() >= jsonModeBlockedUntilMs;
    }

    private LlmTimeoutException timeoutException(Throwable cause) {
        return new LlmTimeoutException(AI_TIMEOUT + ": LLM 请求超过总时限 "
                + ai.getTotalTimeoutSeconds() + "s", cause);
    }

    private String shortMessage(Throwable e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return e.getClass().getSimpleName();
        }
        int newline = message.indexOf('\n');
        String single = newline > 0 ? message.substring(0, newline) : message;
        String redacted = SecretRedactor.redact(single);
        return redacted.length() > 300 ? redacted.substring(0, 300) + "…" : redacted;
    }

    /** Snapshot exposed through the AI health indicator; never contains the key. */
    public Stats stats() {
        return new Stats(true, ai.getModel(), ai.getBaseUrl(), jsonModeAvailable(),
                calls.get(), successes.get(), failures.get(), retries.get(), jsonFallbacks.get(),
                httpAttempts.get(), lastModel, lastError, lastElapsedMs, lastPromptTokens,
                lastCompletionTokens);
    }

    @Override
    public void close() {
        httpExecutor.shutdownNow();
    }

    public record Stats(boolean available, String configuredModel, String baseUrl,
                        boolean jsonModeSupported, long calls, long successes, long failures,
                        long retries, long jsonFallbacks, long httpAttempts, String lastModel,
                        String lastError, long lastElapsedMs, long lastPromptTokens,
                        long lastCompletionTokens) {
    }

    /** Thrown when the total deadline elapses; the message starts with AI_TIMEOUT. */
    public static class LlmTimeoutException extends RuntimeException {
        public LlmTimeoutException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
