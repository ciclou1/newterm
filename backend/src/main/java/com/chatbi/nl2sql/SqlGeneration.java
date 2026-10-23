package com.chatbi.nl2sql;

import java.util.List;

public record SqlGeneration(
        String sql,
        String explanation,
        double confidence,
        Clarification clarification,
        String mode,
        int promptTokens,
        int completionTokens,
        String model,
        String error) {

    public static SqlGeneration demo(String sql, String explanation) {
        return new SqlGeneration(sql, explanation, 0.75, null, "DEMO", 0, 0, null, null);
    }

    public static SqlGeneration clarify(Clarification clarification, String mode) {
        return new SqlGeneration(null, null, 0, clarification, mode, 0, 0, null, null);
    }

    public static SqlGeneration error(String message, String mode, int promptTokens, int completionTokens, String model) {
        return new SqlGeneration(null, null, 0, null, mode, promptTokens, completionTokens, model, message);
    }

    public boolean hasSql() {
        return sql != null && !sql.isBlank();
    }

    public record Clarification(String question, List<Option> options) {
        public record Option(String label, String question) {
        }
    }
}
