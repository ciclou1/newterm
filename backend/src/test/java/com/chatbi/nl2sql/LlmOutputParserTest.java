package com.chatbi.nl2sql;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LlmOutputParserTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final LlmResponse response = new LlmResponse("", 120, 30, "deepseek-flash", 10);

    @Test
    void parsesPlainJson() {
        SqlGeneration generation = parse("""
                {"sql":"SELECT region FROM v_sales_fact","explanation":"按区域","confidence":0.8}
                """);
        assertThat(generation.hasSql()).isTrue();
        assertThat(generation.sql()).isEqualTo("SELECT region FROM v_sales_fact");
        assertThat(generation.confidence()).isEqualTo(0.8);
        assertThat(generation.promptTokens()).isEqualTo(120);
    }

    @Test
    void parsesJsonInsideCodeFenceWithNoise() {
        SqlGeneration generation = parse("""
                Sure, here is the query:
                ```json
                {"sql":"SELECT SUM(amount) AS total FROM v_sales_fact","confidence":0.7}
                ```
                Hope this helps.
                """);
        assertThat(generation.sql()).isEqualTo("SELECT SUM(amount) AS total FROM v_sales_fact");
    }

    @Test
    void extractsFirstBalancedJsonObjectAndIgnoresTrailingExample() {
        SqlGeneration generation = parse("""
                {"sql":"SELECT COUNT(*) AS order_count FROM v_sales_fact","confidence":0.9}
                另一个示例（不要使用）：{"sql":"SELECT 1 FROM forbidden_table"}
                """);
        assertThat(generation.sql()).isEqualTo("SELECT COUNT(*) AS order_count FROM v_sales_fact");
    }

    @Test
    void stripsSemicolonAndSurroundingFenceInSqlField() {
        SqlGeneration generation = parse("""
                {"sql":"```sql\\nSELECT region FROM v_sales_fact;\\n```"}
                """);
        assertThat(generation.sql()).isEqualTo("SELECT region FROM v_sales_fact");
    }

    @Test
    void parsesClarificationPayload() {
        SqlGeneration generation = parse("""
                {"sql":null,"clarification":{"question":"按金额还是数量？",
                 "options":[{"label":"金额","question":"按销售额统计"},{"label":"数量","question":"按订单数统计"}]}}
                """);
        assertThat(generation.hasSql()).isFalse();
        assertThat(generation.clarification()).isNotNull();
        assertThat(generation.clarification().question()).isEqualTo("按金额还是数量？");
        assertThat(generation.clarification().options()).hasSize(2);
    }

    @Test
    void recoversBareSqlWhenModelIgnoresJsonInstruction() {
        SqlGeneration generation = parse("""
                Here you go:
                SELECT region, SUM(amount) AS sales_amount
                FROM v_sales_fact
                GROUP BY region;
                """);
        assertThat(generation.hasSql()).isTrue();
        assertThat(generation.sql()).startsWith("SELECT region");
        assertThat(generation.sql()).doesNotEndWith(";");
        assertThat(generation.confidence()).isEqualTo(0.5);
    }

    @Test
    void rejectsEmptyAndNonSqlOutputWithoutGuessing() {
        assertThat(parse("   ").hasSql()).isFalse();
        assertThat(parse("   ").error()).contains("空内容");
        SqlGeneration noise = parse("I cannot answer that.");
        assertThat(noise.hasSql()).isFalse();
        assertThat(noise.error()).contains("不是合法 JSON");
    }

    @Test
    void stripsDangerousControlSequencesBeforeParsing() {
        String raw = "\u001B[31m{\"sql\":\"SELECT 1 AS x\"}‮\u0000";
        SqlGeneration generation = parse(raw);
        assertThat(generation.sql()).isEqualTo("SELECT 1 AS x");
        assertThat(LlmOutputParser.sanitize(raw)).doesNotContain("\u001B").doesNotContain("‮")
                .doesNotContain("\u0000");
    }

    private SqlGeneration parse(String raw) {
        return LlmOutputParser.parse(raw, response, mapper);
    }
}
