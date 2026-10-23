package com.chatbi.nl2sql;

public record LlmResponse(String text, int promptTokens, int completionTokens, String model, long elapsedMs) {
}
