package com.chatbi.nl2sql;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Robust parser for model output.
 *
 * <p>LLMs do not reliably honour "output JSON only": providers wrap answers in
 * Markdown fences, prepend prose, append explanations, or (especially on
 * retries) emit a bare SQL statement. This parser accepts those shapes but
 * never guesses a query that is not present, and always returns the usage
 * numbers of the underlying call so accounting stays correct.</p>
 */
@Slf4j
public final class LlmOutputParser {

    private static final Pattern SELECT_START = Pattern.compile("(?i)\\bselect\\b");
    private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-9;?]*[ -/]*[@-~]");
    private static final Pattern BIDI = Pattern.compile("[\\u202A-\\u202E\\u2066-\\u2069\\u200E\\u200F]");

    private LlmOutputParser() {
    }

    public static SqlGeneration parse(String raw, LlmResponse response, ObjectMapper mapper) {
        String cleaned = sanitize(raw);
        if (cleaned.isBlank()) {
            return SqlGeneration.error("模型返回空内容", "AI",
                    response.promptTokens(), response.completionTokens(), response.model());
        }
        String json = extractJsonObject(stripFence(cleaned));
        try {
            JsonNode root = mapper.readTree(json);
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("not a JSON object");
            }
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
            sql = cleanSql(sql);
            String explanation = text(root, "explanation");
            double confidence = root.path("confidence").asDouble(0.7);
            return new SqlGeneration(sql, explanation, confidence, null, "AI",
                    response.promptTokens(), response.completionTokens(), response.model(), null);
        } catch (Exception e) {
            String fallbackSql = extractBareSql(cleaned);
            if (fallbackSql != null) {
                log.debug("Model output was not valid JSON; recovered a bare SELECT statement");
                return new SqlGeneration(fallbackSql, "模型以纯文本返回 SQL", 0.5, null, "AI",
                        response.promptTokens(), response.completionTokens(), response.model(), null);
            }
            return SqlGeneration.error("模型输出不是合法 JSON：" + shortMessage(e), "AI",
                    response.promptTokens(), response.completionTokens(), response.model());
        }
    }

    /** Removes ANSI escapes, bidi overrides and control characters except newline/tab. */
    static String sanitize(String raw) {
        if (raw == null) {
            return "";
        }
        String text = ANSI.matcher(raw).replaceAll("");
        text = BIDI.matcher(text).replaceAll("");
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\t') {
                out.append(c);
            } else if (c == '\r') {
                out.append('\n');
            } else if (Character.isISOControl(c)) {
                out.append(' ');
            } else {
                out.append(c);
            }
        }
        return out.toString().trim();
    }

    static String stripFence(String raw) {
        String result = raw == null ? "" : raw.trim();
        if (!result.startsWith("```")) {
            return result;
        }
        int firstNewline = result.indexOf('\n');
        if (firstNewline < 0) {
            return "";
        }
        result = result.substring(firstNewline + 1);
        int closing = result.indexOf("```");
        if (closing >= 0) {
            result = result.substring(0, closing);
        }
        return result.trim();
    }

    /**
     * Returns the first balanced top-level JSON object. A naive
     * first-{ / last-} slice breaks when the model appends prose containing
     * braces or another example object after the real answer.
     */
    static String extractJsonObject(String raw) {
        String text = raw == null ? "" : raw;
        int start = text.indexOf('{');
        if (start < 0) {
            return text;
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return text.substring(start);
    }

    static String extractBareSql(String raw) {
        Matcher matcher = SELECT_START.matcher(raw);
        if (!matcher.find()) {
            return null;
        }
        String candidate = raw.substring(matcher.start()).trim();
        int fence = candidate.indexOf("```");
        if (fence >= 0) {
            candidate = candidate.substring(0, fence).trim();
        }
        return cleanSqlCandidate(candidate);
    }

    static String cleanSql(String sql) {
        String cleaned = stripFence(sql.trim());
        int fence = cleaned.indexOf("```");
        if (fence >= 0) {
            cleaned = cleaned.substring(0, fence);
        }
        return cleanSqlCandidate(cleaned.trim());
    }

    private static String cleanSqlCandidate(String candidate) {
        String cleaned = candidate.trim();
        while (cleaned.endsWith(";")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1).trim();
        }
        return cleaned.isBlank() ? null : cleaned;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String shortMessage(Exception e) {
        String message = e.getMessage();
        if (message == null) {
            return e.getClass().getSimpleName();
        }
        int newline = message.indexOf('\n');
        String single = newline > 0 ? message.substring(0, newline) : message;
        return single.length() > 200 ? single.substring(0, 200) + "…" : single;
    }
}
