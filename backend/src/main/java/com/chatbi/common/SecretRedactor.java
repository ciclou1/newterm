package com.chatbi.common;

import java.util.regex.Pattern;

/**
 * Redacts credential-shaped strings from gateway errors before they reach
 * health details, audit rows or user-visible messages. OpenAI-compatible
 * providers often echo the submitted key (sometimes masked, sometimes not)
 * inside 401/429 response bodies.
 */
public final class SecretRedactor {

    private static final Pattern API_KEY = Pattern.compile("sk-[A-Za-z0-9_-]{8,}");
    private static final Pattern BEARER = Pattern.compile("Bearer\\s+\\S+", Pattern.CASE_INSENSITIVE);
    private static final String API_KEY_MASK = "sk-***";
    private static final String BEARER_MASK = "Bearer ***";

    private SecretRedactor() {
    }

    public static String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String withoutKeys = API_KEY.matcher(text).replaceAll(API_KEY_MASK);
        return BEARER.matcher(withoutKeys).replaceAll(BEARER_MASK);
    }
}
