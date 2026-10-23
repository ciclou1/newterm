package com.chatbi.nl2sql;

import com.chatbi.sql.GuardCheck;
import com.chatbi.sql.QueryExecutor;
import com.chatbi.sql.SqlGuardResult;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Full outcome of one NL2SQL turn. The API layer renders this directly and the
 * audit layer persists the trace; no hidden second execution path.
 */
public record AgentResult(
        String status,
        String question,
        String mode,
        String generatedSql,
        String finalSql,
        String explanation,
        double confidence,
        Clarification clarification,
        GuardInfo guard,
        QueryResult result,
        Usage usage,
        Performance performance,
        SchemaInfo schema,
        Failure failure,
        List<Attempt> trace) {

    public boolean ok() {
        return "OK".equals(status);
    }

    public record Clarification(String question, List<SqlGeneration.Clarification.Option> options) {
    }

    public record GuardInfo(List<GuardCheck> checks, List<String> tables,
                            List<SqlGuardResult.RowScope> rowScopes) {
        public static GuardInfo empty() {
            return new GuardInfo(List.of(), List.of(), List.of());
        }
    }

    public record QueryResult(List<QueryExecutor.ResultColumn> columns, List<Map<String, Object>> rows,
                              boolean truncated, int rowCount) {
        public static QueryResult empty() {
            return new QueryResult(List.of(), List.of(), false, 0);
        }
    }

    public record Usage(int attempts, int promptTokens, int completionTokens, BigDecimal costUsd, String model) {
        public static Usage empty() {
            return new Usage(0, 0, 0, BigDecimal.ZERO, null);
        }
    }

    public record Performance(long linkMs, long generateMs, long guardMs, long executeMs, long totalMs,
                              Double explainCost, List<String> planNodes) {
    }

    public record SchemaInfo(int tableCount, int metricCount, int columnCount, int exampleCount,
                             List<String> hits) {
        public static SchemaInfo empty() {
            return new SchemaInfo(0, 0, 0, 0, List.of());
        }
    }

    public record Failure(String stage, String code, String message) {
    }

    public record Attempt(int index, String mode, String stage, String sql, String code,
                          String message, boolean passed, long elapsedMs) {
    }

    public static AgentResult clarification(String question, SqlGeneration.Clarification clarification,
                                            String mode, SchemaInfo schema, Performance performance,
                                            Usage usage, List<Attempt> trace) {
        return new AgentResult("CLARIFY", question, mode, null, null, null, 0,
                new Clarification(clarification.question(), clarification.options()),
                GuardInfo.empty(), QueryResult.empty(), usage, performance, schema,
                new Failure("clarify", "CLARIFY", clarification.question()), trace);
    }

    public static AgentResult failure(String status, String question, String mode, String generatedSql,
                                      String finalSql, String explanation, Clarification clarification,
                                      GuardInfo guard, QueryResult result, Usage usage,
                                      Performance performance, SchemaInfo schema, Failure failure,
                                      List<Attempt> trace) {
        return new AgentResult(status, question, mode, generatedSql, finalSql, explanation,
                clarification == null ? 0 : 0.4, clarification, guard, result, usage,
                performance, schema, failure, trace);
    }
}
