package com.chatbi.nl2sql;

import com.chatbi.config.ChatBiProperties;
import com.chatbi.domain.entity.AppUser;
import com.chatbi.linking.SchemaContext;
import com.chatbi.linking.SchemaLinker;
import com.chatbi.sql.ExplainCostService;
import com.chatbi.sql.QueryExecutor;
import com.chatbi.sql.SqlGuardResult;
import com.chatbi.sql.SqlGuardService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class Nl2SqlAgentTest {

    private static final String BAD_STRUCTURE =
            "WITH x AS (SELECT * FROM v_sales_fact) SELECT * FROM x";
    private static final String GOOD_SQL =
            "SELECT region, SUM(sales_amount) AS total FROM v_sales_fact GROUP BY region";

    private final SchemaLinker schemaLinker = mock(SchemaLinker.class);
    private final AiSqlGenerator aiGenerator = mock(AiSqlGenerator.class);
    private final DemoSqlGenerator demoGenerator = mock(DemoSqlGenerator.class);
    private final SqlGuardService guardService = mock(SqlGuardService.class);
    private final ExplainCostService explainCostService = mock(ExplainCostService.class);
    private final QueryExecutor queryExecutor = mock(QueryExecutor.class);
    private ChatBiProperties properties;
    private Nl2SqlAgent agent;

    @BeforeEach
    void setUp() {
        properties = new ChatBiProperties();
        properties.getQuery().setMaxRepairs(2);
        agent = new Nl2SqlAgent(schemaLinker, aiGenerator, demoGenerator, guardService,
                explainCostService, queryExecutor, properties);
        when(schemaLinker.link(anyString())).thenReturn(schema());
    }

    @Test
    void succeedsThroughTheAiPathWithoutDegradation() {
        when(aiGenerator.available()).thenReturn(true);
        when(aiGenerator.generate(any(), any(), any(), any(), any(), any()))
                .thenReturn(sql(GOOD_SQL));
        when(guardService.guard(anyString(), any())).thenReturn(passed(GOOD_SQL));
        when(explainCostService.explain(GOOD_SQL)).thenReturn(explainOk());
        when(queryExecutor.execute(GOOD_SQL)).thenReturn(rows());

        AgentResult result = agent.ask("今年各区域销售额", user(), List.of());

        assertThat(result.status()).isEqualTo("OK");
        assertThat(result.mode()).isEqualTo("AI");
        assertThat(result.degraded()).isFalse();
        assertThat(result.usage().attempts()).isEqualTo(1);
        assertThat(result.finalSql()).isEqualTo(GOOD_SQL);
        assertThat(result.result().rows()).hasSize(1);
    }

    @Test
    void repairsAStructuralViolationThenSucceeds() {
        when(aiGenerator.available()).thenReturn(true);
        when(aiGenerator.generate(any(), any(), any(), any(), any(), any()))
                .thenReturn(sql(BAD_STRUCTURE), sql(GOOD_SQL));
        when(guardService.guard(anyString(), any()))
                .thenReturn(SqlGuardResult.rejected("NESTED_QUERY",
                        "detected CTE / subquery", List.of()), passed(GOOD_SQL));
        when(explainCostService.explain(GOOD_SQL)).thenReturn(explainOk());
        when(queryExecutor.execute(GOOD_SQL)).thenReturn(rows());

        AgentResult result = agent.ask("今年各区域销售额", user(), List.of());

        assertThat(result.status()).isEqualTo("OK");
        assertThat(result.mode()).isEqualTo("AI");
        assertThat(result.usage().attempts()).isEqualTo(2);
        assertThat(result.trace()).anyMatch(attempt -> "NESTED_QUERY".equals(attempt.code()));

        ArgumentCaptor<String> feedback = ArgumentCaptor.forClass(String.class);
        verify(aiGenerator, times(2)).generate(any(), any(), any(), any(), any(), feedback.capture());
        assertThat(feedback.getAllValues().get(1))
                .contains("NESTED_QUERY")
                .contains("单层 SELECT")
                .contains("UNION");
    }

    @Test
    void returnsErrorWhenStructuralRepairIsExhausted() {
        when(aiGenerator.available()).thenReturn(true);
        when(aiGenerator.generate(any(), any(), any(), any(), any(), any()))
                .thenReturn(sql(BAD_STRUCTURE));
        when(guardService.guard(anyString(), any())).thenReturn(SqlGuardResult.rejected(
                "NESTED_QUERY", "detected CTE / subquery", List.of()));

        AgentResult result = agent.ask("今年各区域销售额", user(), List.of());

        assertThat(result.status()).isEqualTo("ERROR");
        assertThat(result.failure().code()).isEqualTo("NESTED_QUERY");
        assertThat(result.usage().attempts()).isEqualTo(3);
        verify(aiGenerator, times(3)).generate(any(), any(), any(), any(), any(), any());
        verifyNoInteractions(explainCostService, queryExecutor);
    }

    @Test
    void rejectsHardPolicyViolationsWithoutRepair() {
        when(aiGenerator.available()).thenReturn(true);
        when(aiGenerator.generate(any(), any(), any(), any(), any(), any()))
                .thenReturn(sql("DELETE FROM v_sales_fact"));
        when(guardService.guard(anyString(), any())).thenReturn(SqlGuardResult.rejected(
                "WRITE_KEYWORD", "write keyword detected", List.of()));

        AgentResult result = agent.ask("删除全部销售数据", user(), List.of());

        assertThat(result.status()).isEqualTo("BLOCKED");
        assertThat(result.failure().code()).isEqualTo("WRITE_KEYWORD");
        assertThat(result.usage().attempts()).isEqualTo(1);
        verify(aiGenerator, times(1)).generate(any(), any(), any(), any(), any(), any());
        verifyNoInteractions(demoGenerator, explainCostService, queryExecutor);
    }

    @Test
    void aiFailureDoesNotSilentlyFallBackToTheDemoPlannerByDefault() {
        when(aiGenerator.available()).thenReturn(true);
        when(aiGenerator.generate(any(), any(), any(), any(), any(), any()))
                .thenReturn(SqlGeneration.error("模型调用失败", "AI", 12, 3, "gpt-test"));

        AgentResult result = agent.ask("今年各区域销售额", user(), List.of());

        assertThat(result.status()).isEqualTo("ERROR");
        assertThat(result.mode()).isEqualTo("AI");
        assertThat(result.degraded()).isFalse();
        assertThat(result.failure().code()).isEqualTo("GENERATION_FAILED");
        verifyNoInteractions(demoGenerator, guardService, explainCostService, queryExecutor);
    }

    @Test
    void demoFallbackIsExplicitAndMarkedDegradedWhenEnabled() {
        properties.getAi().setAllowDemoFallback(true);
        when(aiGenerator.available()).thenReturn(true);
        when(aiGenerator.generate(any(), any(), any(), any(), any(), any()))
                .thenReturn(SqlGeneration.error("模型调用失败", "AI", 12, 3, "gpt-test"));
        when(demoGenerator.generate(any(), any(), any(), any()))
                .thenReturn(SqlGeneration.demo(GOOD_SQL, "规则引擎"));
        when(guardService.guard(anyString(), any())).thenReturn(passed(GOOD_SQL));
        when(explainCostService.explain(GOOD_SQL)).thenReturn(explainOk());
        when(queryExecutor.execute(GOOD_SQL)).thenReturn(rows());

        AgentResult result = agent.ask("今年各区域销售额", user(), List.of());

        assertThat(result.status()).isEqualTo("OK");
        assertThat(result.mode()).isEqualTo("DEMO");
        assertThat(result.degraded()).isTrue();
        assertThat(result.degradedReason()).contains("ALLOW_DEMO_FALLBACK");
    }

    @Test
    void demoIsThePrimaryPlannerWhenAiIsDisabled() {
        when(aiGenerator.available()).thenReturn(false);
        when(demoGenerator.generate(any(), any(), any(), any()))
                .thenReturn(SqlGeneration.demo(GOOD_SQL, "规则引擎"));
        when(guardService.guard(eq(GOOD_SQL), any())).thenReturn(passed(GOOD_SQL));
        when(explainCostService.explain(GOOD_SQL)).thenReturn(explainOk());
        when(queryExecutor.execute(GOOD_SQL)).thenReturn(rows());

        AgentResult result = agent.ask("今年各区域销售额", user(), List.of());

        assertThat(result.status()).isEqualTo("OK");
        assertThat(result.mode()).isEqualTo("DEMO");
        assertThat(result.degraded()).isFalse();
    }

    @Test
    void passesThePreviousSqlToTheDemoPlannerForFollowUps() {
        String previousSql = "SELECT region, SUM(amount) AS sales_amount FROM v_sales_fact GROUP BY region";
        when(aiGenerator.available()).thenReturn(false);
        when(demoGenerator.generate(any(), any(), any(), any()))
                .thenReturn(SqlGeneration.demo(GOOD_SQL, "规则引擎"));
        when(guardService.guard(eq(GOOD_SQL), any())).thenReturn(passed(GOOD_SQL));
        when(explainCostService.explain(GOOD_SQL)).thenReturn(explainOk());
        when(queryExecutor.execute(GOOD_SQL)).thenReturn(rows());

        agent.ask("那去年呢", user(),
                List.of(new ConversationTurn("今年各区域销售额", previousSql, "华东最高")));

        verify(demoGenerator).generate(eq("那去年呢"), any(), eq("今年各区域销售额"), eq(previousSql));
    }

    @Test
    void computesUsageCostFromConfiguredPrices() {
        properties.getAi().setInputCostPerMillion(new BigDecimal("1.00"));
        properties.getAi().setOutputCostPerMillion(new BigDecimal("2.00"));
        when(aiGenerator.available()).thenReturn(true);
        when(aiGenerator.generate(any(), any(), any(), any(), any(), any()))
                .thenReturn(new SqlGeneration(GOOD_SQL, "说明", 0.9, null, "AI",
                        1_000_000, 500_000, "deepseek-flash", null));
        when(guardService.guard(anyString(), any())).thenReturn(passed(GOOD_SQL));
        when(explainCostService.explain(GOOD_SQL)).thenReturn(explainOk());
        when(queryExecutor.execute(GOOD_SQL)).thenReturn(rows());

        AgentResult result = agent.ask("今年各区域销售额", user(), List.of());

        // 1M input tokens * 1.00 + 0.5M output tokens * 2.00 = 2.00
        assertThat(result.usage().promptTokens()).isEqualTo(1_000_000);
        assertThat(result.usage().completionTokens()).isEqualTo(500_000);
        assertThat(result.usage().costUsd()).isEqualByComparingTo("2.000000");
    }

    @Test
    void reportsZeroCostWhenPricesAreNotConfigured() {
        when(aiGenerator.available()).thenReturn(true);
        when(aiGenerator.generate(any(), any(), any(), any(), any(), any()))
                .thenReturn(sql(GOOD_SQL));
        when(guardService.guard(anyString(), any())).thenReturn(passed(GOOD_SQL));
        when(explainCostService.explain(GOOD_SQL)).thenReturn(explainOk());
        when(queryExecutor.execute(GOOD_SQL)).thenReturn(rows());

        AgentResult result = agent.ask("今年各区域销售额", user(), List.of());

        assertThat(result.usage().promptTokens()).isEqualTo(100);
        assertThat(result.usage().costUsd()).isEqualByComparingTo("0");
        assertThat(properties.getAi().costConfigured()).isFalse();
    }

    private SchemaContext schema() {
        return new SchemaContext(List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), "", 1);
    }

    private AppUser user() {
        AppUser user = new AppUser();
        user.setUsername("lisi");
        user.setDisplayName("李思");
        user.setRole("平台管理员");
        user.setIsAdmin(true);
        user.setEnabled(true);
        return user;
    }

    private SqlGeneration sql(String sql) {
        return new SqlGeneration(sql, "说明", 0.9, null, "AI", 100, 20, "gpt-test", null);
    }

    private SqlGuardResult passed(String sql) {
        return new SqlGuardResult(true, sql, null, null, List.of("v_sales_fact"),
                List.of(), List.of(), false);
    }

    private ExplainCostService.ExplainResult explainOk() {
        return new ExplainCostService.ExplainResult(null, 100, List.of(), null);
    }

    private QueryExecutor.QueryExecution rows() {
        return new QueryExecutor.QueryExecution(true,
                List.of(new QueryExecutor.ResultColumn("total", "int8")),
                List.of(Map.of("total", BigDecimal.TEN)), true, false, 3, null);
    }
}
