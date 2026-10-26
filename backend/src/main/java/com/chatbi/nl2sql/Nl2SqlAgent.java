package com.chatbi.nl2sql;

import com.chatbi.config.ChatBiProperties;
import com.chatbi.domain.entity.AppUser;
import com.chatbi.linking.SchemaContext;
import com.chatbi.linking.SchemaLinker;
import com.chatbi.sql.ExplainCostService;
import com.chatbi.sql.QueryExecutor;
import com.chatbi.sql.SqlGuardResult;
import com.chatbi.sql.SqlGuardService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Bounded NL2SQL agent:
 * schema linking -> generation -> AST guard + row-level permission -> EXPLAIN
 * -> read-only execution -> error feedback repair (at most N times).
 */
@Slf4j
@Component
public class Nl2SqlAgent {

    /**
     * Policy violations that must never be handed back to the model: they are
     * either destructive, escape the read-only boundary, or deny data access.
     * Everything else (CTE/subquery/UNION, unknown table or column, syntax
     * errors) is structurally repairable and goes through the bounded repair
     * loop with an explicit rewrite instruction.
     */
    private static final Set<String> HARD_POLICY_CODES = Set.of(
            "WRITE_KEYWORD", "DANGEROUS_FUNCTION", "SYSTEM_SCHEMA", "MULTI_STATEMENT",
            "SELECT_INTO", "LOCKING", "SQL_TOO_LONG", "ROW_LEVEL_DENIED");

    private static final Set<String> STRUCTURAL_REPAIR_CODES = Set.of(
            "NESTED_QUERY", "SET_OPERATION", "NOT_SELECT", "UNSUPPORTED_SELECT",
            "UNSUPPORTED_FROM", "SCHEMA_DENIED", "TABLE_DENIED", "NO_FROM",
            "NO_TABLE", "PARSE_ERROR");

    private final SchemaLinker schemaLinker;
    private final AiSqlGenerator aiGenerator;
    private final DemoSqlGenerator demoGenerator;
    private final SqlGuardService guardService;
    private final ExplainCostService explainCostService;
    private final QueryExecutor queryExecutor;
    private final ChatBiProperties properties;

    public Nl2SqlAgent(SchemaLinker schemaLinker,
                       AiSqlGenerator aiGenerator,
                       DemoSqlGenerator demoGenerator,
                       SqlGuardService guardService,
                       ExplainCostService explainCostService,
                       QueryExecutor queryExecutor,
                       ChatBiProperties properties) {
        this.schemaLinker = schemaLinker;
        this.aiGenerator = aiGenerator;
        this.demoGenerator = demoGenerator;
        this.guardService = guardService;
        this.explainCostService = explainCostService;
        this.queryExecutor = queryExecutor;
        this.properties = properties;
    }

    public AgentResult ask(String question, AppUser user, List<ConversationTurn> history) {
        long start = System.currentTimeMillis();
        if (question == null || question.isBlank()) {
            return failure("ERROR", question, aiGenerator.available() ? "AI" : "DEMO",
                    AgentResult.SchemaInfo.empty(), new AgentResult.Performance(0, 0, 0, 0,
                            System.currentTimeMillis() - start, null, List.of()),
                    AgentResult.Usage.empty(), new AgentResult.Failure("input", "EMPTY_QUESTION",
                            "问题不能为空"), List.of());
        }
        List<ConversationTurn> turns = history == null ? List.of() : history;
        String previousQuestion = turns.isEmpty() ? null : turns.getLast().question();
        String previousSql = turns.isEmpty() ? null : turns.getLast().sql();

        long linkStart = System.currentTimeMillis();
        SchemaContext schema;
        try {
            schema = schemaLinker.link(question);
        } catch (Exception e) {
            log.error("Schema linking failed", e);
            return failure("ERROR", question, aiGenerator.available() ? "AI" : "DEMO",
                    AgentResult.SchemaInfo.empty(), new AgentResult.Performance(0, 0, 0, 0,
                            System.currentTimeMillis() - start, null, List.of()),
                    AgentResult.Usage.empty(), new AgentResult.Failure("schema-link", "SCHEMA_LINK_ERROR",
                            shortMessage(e)), List.of());
        }
        long linkMs = System.currentTimeMillis() - linkStart;
        AgentResult.SchemaInfo schemaInfo = schemaInfo(schema);

        List<AgentResult.Attempt> trace = new ArrayList<>();
        int maxAttempts = Math.max(1, properties.getQuery().getMaxRepairs() + 1);
        int attempts = 0;
        int promptTokens = 0;
        int completionTokens = 0;
        String model = null;
        long generateMs = 0;
        long guardMs = 0;
        long executeMs = 0;
        String mode = aiGenerator.available() ? "AI" : "DEMO";
        String generatedSql = null;
        String finalSql = null;
        String explanation = null;
        AgentResult.GuardInfo guardInfo = AgentResult.GuardInfo.empty();
        AgentResult.QueryResult queryResult = AgentResult.QueryResult.empty();
        Double explainCost = null;
        List<String> planNodes = List.of();
        String stage = "generate";
        String code = "NO_ATTEMPT";
        String error = null;
        String feedback = null;
        String previousAttemptSql = previousSql;
        boolean repairPossible = aiGenerator.available();
        boolean degraded = false;
        String degradedReason = null;

        while (attempts < maxAttempts) {
            attempts++;
            long genStart = System.currentTimeMillis();
            GenerationOutcome outcome = generate(question, schema, user, turns,
                    previousAttemptSql, feedback, previousQuestion, trace, attempts);
            generateMs += System.currentTimeMillis() - genStart;
            SqlGeneration generation = outcome.generation();
            mode = outcome.mode();
            degraded = outcome.degraded();
            degradedReason = outcome.degradedReason();
            promptTokens += generation.promptTokens();
            completionTokens += generation.completionTokens();
            if (generation.model() != null && !generation.model().isBlank()) {
                model = generation.model();
            }

            if (generation.clarification() != null) {
                AgentResult.Usage usage = usage(attempts, promptTokens, completionTokens, model);
                AgentResult.Performance performance = performance(linkMs, generateMs, guardMs, executeMs,
                        start, explainCost, planNodes);
                trace.add(new AgentResult.Attempt(attempts, mode, "clarify", null, "CLARIFY",
                        generation.clarification().question(), false, 0));
                return AgentResult.clarification(question, generation.clarification(), mode, schemaInfo,
                        performance, usage, trace, degraded, degradedReason);
            }
            if (!generation.hasSql()) {
                stage = "generate";
                code = "GENERATION_FAILED";
                error = generation.error() == null ? "模型未生成 SQL" : generation.error();
                trace.add(new AgentResult.Attempt(attempts, mode, stage, null, code, error, false, 0));
                break;
            }

            generatedSql = generation.sql();
            explanation = generation.explanation();
            previousAttemptSql = generatedSql;
            trace.add(new AgentResult.Attempt(attempts, mode, "generate", generatedSql, null, null, true,
                    System.currentTimeMillis() - genStart));

            long guardStart = System.currentTimeMillis();
            SqlGuardResult guard = guardService.guard(generatedSql, user);
            guardMs += System.currentTimeMillis() - guardStart;
            guardInfo = new AgentResult.GuardInfo(guard.checks(), guard.tables(), guard.rowScopes());
            if (!guard.passed()) {
                stage = "guard";
                code = guard.code();
                error = guard.message();
                trace.add(new AgentResult.Attempt(attempts, mode, stage, generatedSql, code, error, false,
                        System.currentTimeMillis() - guardStart));
                if (guard.permissionDenied() || HARD_POLICY_CODES.contains(guard.code())) {
                    return failure("BLOCKED", question, mode, generatedSql, null, explanation,
                            guardInfo, queryResult, usage(attempts, promptTokens, completionTokens, model),
                            performance(linkMs, generateMs, guardMs, executeMs, start, explainCost, planNodes),
                            schemaInfo, new AgentResult.Failure(stage, code, error), trace,
                            degraded, degradedReason);
                }
                feedback = repairFeedback(code, error);
                if (!repairPossible || "DEMO".equals(mode) || attempts >= maxAttempts) {
                    break;
                }
                continue;
            }
            finalSql = guard.sql();
            trace.add(new AgentResult.Attempt(attempts, mode, "guard", finalSql, null, null, true,
                    System.currentTimeMillis() - guardStart));

            long explainStart = System.currentTimeMillis();
            ExplainCostService.ExplainResult plan = explainCostService.explain(guard.sql());
            executeMs += System.currentTimeMillis() - explainStart;
            if (!plan.ok()) {
                stage = "explain";
                code = "EXPLAIN_FAILED";
                error = plan.error();
                trace.add(new AgentResult.Attempt(attempts, mode, stage, finalSql, code, error, false,
                        System.currentTimeMillis() - explainStart));
                feedback = "EXPLAIN 执行失败：" + error + "。请修复 SQL。";
                if (!repairPossible || "DEMO".equals(mode) || attempts >= maxAttempts) {
                    break;
                }
                continue;
            }
            explainCost = plan.totalCost();
            planNodes = plan.nodes();
            if (plan.totalCost() > properties.getQuery().getMaxExplainCost()) {
                stage = "explain";
                code = "EXPLAIN_COST_EXCEEDED";
                error = "预估代价 " + Math.round(plan.totalCost()) + " 超过阈值 "
                        + properties.getQuery().getMaxExplainCost() + "，已拒绝执行";
                trace.add(new AgentResult.Attempt(attempts, mode, stage, finalSql, code, error, false,
                        System.currentTimeMillis() - explainStart));
                return failure("BLOCKED", question, mode, generatedSql, finalSql, explanation,
                        guardInfo, queryResult, usage(attempts, promptTokens, completionTokens, model),
                        performance(linkMs, generateMs, guardMs, executeMs, start, explainCost, planNodes),
                        schemaInfo, new AgentResult.Failure(stage, code, error), trace,
                        degraded, degradedReason);
            }
            trace.add(new AgentResult.Attempt(attempts, mode, "explain", finalSql, null,
                    "cost=" + Math.round(plan.totalCost()), true, System.currentTimeMillis() - explainStart));

            long execStart = System.currentTimeMillis();
            QueryExecutor.QueryExecution execution = queryExecutor.execute(guard.sql());
            executeMs += System.currentTimeMillis() - execStart;
            if (!execution.success()) {
                stage = "execute";
                code = "EXECUTION_FAILED";
                error = execution.error();
                trace.add(new AgentResult.Attempt(attempts, mode, stage, finalSql, code, error, false,
                        System.currentTimeMillis() - execStart));
                feedback = "SQL 在只读库执行失败：" + error + "。请修复后重新生成。";
                if (!repairPossible || "DEMO".equals(mode) || attempts >= maxAttempts) {
                    break;
                }
                continue;
            }

            queryResult = new AgentResult.QueryResult(execution.columns(), execution.rows(),
                    execution.truncated(), execution.rows().size());
            trace.add(new AgentResult.Attempt(attempts, mode, "execute", finalSql, null,
                    execution.rows().size() + " rows", true, execution.elapsedMs()));
            AgentResult.Usage usage = usage(attempts, promptTokens, completionTokens, model);
            AgentResult.Performance performance = performance(linkMs, generateMs, guardMs, executeMs,
                    start, explainCost, planNodes);
            return new AgentResult("OK", question, mode, generatedSql, finalSql, explanation,
                    generation.confidence(), null, guardInfo, queryResult, usage, performance,
                    schemaInfo, new AgentResult.Failure(null, null, null), trace,
                    degraded, degradedReason);
        }

        String status = "guard".equals(stage) && isPolicyCode(code) ? "BLOCKED" : "ERROR";
        return failure(status, question, mode, generatedSql, finalSql, explanation, guardInfo, queryResult,
                usage(attempts, promptTokens, completionTokens, model),
                performance(linkMs, generateMs, guardMs, executeMs, start, explainCost, planNodes),
                schemaInfo, new AgentResult.Failure(stage, code, error), trace,
                degraded, degradedReason);
    }

    private GenerationOutcome generate(String question, SchemaContext schema, AppUser user,
                                       List<ConversationTurn> history, String previousSql, String feedback,
                                       String previousQuestion, List<AgentResult.Attempt> trace, int index) {
        if (!aiGenerator.available()) {
            // AI is disabled by configuration: the deterministic rule engine is
            // the primary planner, not a degraded fallback.
            return new GenerationOutcome(demoGenerator.generate(question, schema, previousQuestion, previousSql),
                    "DEMO", true, false, null);
        }
        SqlGeneration ai = aiGenerator.generate(question, schema, user, history, previousSql, feedback);
        if (ai.hasSql() || ai.clarification() != null) {
            return new GenerationOutcome(ai, "AI", false, false, null);
        }
        trace.add(new AgentResult.Attempt(index, "AI", "generate", null,
                "AI_FALLBACK", ai.error(), false, 0));
        if (!properties.getAi().isAllowDemoFallback()) {
            // Fail closed: never silently substitute a rule-engine answer for a
            // failed LLM call. The caller surfaces an ERROR with the reason.
            return new GenerationOutcome(ai, "AI", false, false, null);
        }
        SqlGeneration demo = demoGenerator.generate(question, schema, previousQuestion, previousSql);
        if (demo.hasSql() || demo.clarification() != null) {
            String reason = "AI 生成失败，已按 CHATBI_AI_ALLOW_DEMO_FALLBACK=true 降级到规则引擎"
                    + (ai.error() == null ? "" : "：" + ai.error());
            return new GenerationOutcome(demo, "DEMO", true, true, reason);
        }
        return new GenerationOutcome(ai, "AI", false, false, null);
    }

    private String repairFeedback(String code, String message) {
        if (STRUCTURAL_REPAIR_CODES.contains(code)) {
            return "SQL 结构不符合平台策略 [" + code + "]：" + message
                    + "。请改写为单层 SELECT：禁止 WITH/CTE、子查询、UNION/INTERSECT/EXCEPT；"
                    + "需要聚合时直接使用 GROUP BY、HAVING 与聚合函数；只能引用语义 Schema 中的表和字段。"
                    + "只输出 JSON。";
        }
        return "安全校验未通过 [" + code + "]：" + message + "。请修复后重新生成。";
    }

    private boolean isPolicyCode(String code) {
        return code != null && (HARD_POLICY_CODES.contains(code) || code.startsWith("EXPLAIN_COST"));
    }

    private AgentResult.Usage usage(int attempts, int promptTokens, int completionTokens, String model) {
        BigDecimal input = BigDecimal.valueOf(promptTokens)
                .multiply(properties.getAi().getInputCostPerMillion())
                .divide(BigDecimal.valueOf(1_000_000), 6, RoundingMode.HALF_UP);
        BigDecimal output = BigDecimal.valueOf(completionTokens)
                .multiply(properties.getAi().getOutputCostPerMillion())
                .divide(BigDecimal.valueOf(1_000_000), 6, RoundingMode.HALF_UP);
        return new AgentResult.Usage(attempts, promptTokens, completionTokens,
                input.add(output).setScale(6, RoundingMode.HALF_UP), model);
    }

    private AgentResult.Performance performance(long linkMs, long generateMs, long guardMs, long executeMs,
                                                long start, Double explainCost, List<String> planNodes) {
        return new AgentResult.Performance(linkMs, generateMs, guardMs, executeMs,
                System.currentTimeMillis() - start, explainCost, planNodes == null ? List.of() : planNodes);
    }

    private AgentResult.SchemaInfo schemaInfo(SchemaContext schema) {
        List<String> hits = new ArrayList<>();
        schema.tableHits().stream().limit(4).forEach(h ->
                hits.add("TABLE " + h.key() + " · " + h.label()));
        schema.metricHits().stream().limit(4).forEach(h ->
                hits.add("METRIC " + h.key() + " · " + h.label()));
        schema.synonymHits().stream().limit(4).forEach(h -> hits.add("SYNONYM " + h));
        int columns = schema.tables().stream().mapToInt(t -> t.columns().size()).sum();
        return new AgentResult.SchemaInfo(schema.tables().size(), schema.metrics().size(), columns,
                schema.examples().size(), List.copyOf(hits));
    }

    private AgentResult failure(String status, String question, String mode, AgentResult.SchemaInfo schema,
                                AgentResult.Performance performance, AgentResult.Usage usage,
                                AgentResult.Failure failure, List<AgentResult.Attempt> trace) {
        return AgentResult.failure(status, question, mode, null, null, null, null,
                AgentResult.GuardInfo.empty(), AgentResult.QueryResult.empty(), usage, performance,
                schema, failure, trace);
    }

    private AgentResult failure(String status, String question, String mode, String generatedSql,
                                String finalSql, String explanation, AgentResult.GuardInfo guard,
                                AgentResult.QueryResult result, AgentResult.Usage usage,
                                AgentResult.Performance performance, AgentResult.SchemaInfo schema,
                                AgentResult.Failure failure, List<AgentResult.Attempt> trace,
                                boolean degraded, String degradedReason) {
        return AgentResult.failure(status, question, mode, generatedSql, finalSql, explanation, null,
                guard, result, usage, performance, schema, failure, trace, degraded, degradedReason);
    }

    private String shortMessage(Exception e) {
        String message = e.getMessage();
        if (message == null) {
            return e.getClass().getSimpleName();
        }
        int newline = message.indexOf('\n');
        return newline > 0 ? message.substring(0, newline) : message;
    }

    private record GenerationOutcome(SqlGeneration generation, String mode, boolean fallback,
                                     boolean degraded, String degradedReason) {
    }
}
