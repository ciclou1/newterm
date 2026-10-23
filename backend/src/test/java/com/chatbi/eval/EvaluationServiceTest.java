package com.chatbi.eval;

import com.chatbi.common.ChatBiException;
import com.chatbi.config.ChatBiProperties;
import com.chatbi.domain.entity.AppUser;
import com.chatbi.nl2sql.AgentResult;
import com.chatbi.nl2sql.Nl2SqlAgent;
import com.chatbi.service.CurrentUserService;
import com.chatbi.sql.QueryExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EvaluationServiceTest {

    private static final String GOLDEN_SQL = "SELECT SUM(amount) AS total FROM v_sales_fact";
    private static final String GENERATED_SQL = "SELECT SUM(sales_amount) AS total FROM v_sales_fact";

    private final Nl2SqlAgent agent = mock(Nl2SqlAgent.class);
    private final QueryExecutor queryExecutor = mock(QueryExecutor.class);
    private final CurrentUserService currentUserService = mock(CurrentUserService.class);
    private final EvaluationService service = new EvaluationService(agent, queryExecutor,
            currentUserService, new ObjectMapper(), new ChatBiProperties());

    @Test
    void passesWhenTheAgentResultMatchesTheGoldenResult() {
        when(agent.ask(anyString(), any(), any())).thenReturn(answer(rows(new BigDecimal("10.5"))));
        when(queryExecutor.execute(GOLDEN_SQL)).thenReturn(golden(rows(new BigDecimal("10.5"))));

        EvaluationService.CaseResult result = service.evaluate(question(), admin());

        assertThat(result.passed()).isTrue();
        assertThat(result.message()).isEqualTo("结果一致");
    }

    @Test
    void failsWhenTheAgentResultDiffersFromTheGoldenResult() {
        when(agent.ask(anyString(), any(), any())).thenReturn(answer(rows(new BigDecimal("11"))));
        when(queryExecutor.execute(GOLDEN_SQL)).thenReturn(golden(rows(new BigDecimal("10.5"))));

        EvaluationService.CaseResult result = service.evaluate(question(), admin());

        assertThat(result.passed()).isFalse();
        assertThat(result.code()).isEqualTo("RESULT_MISMATCH");
    }

    @Test
    void reusesTheAgentResultInsteadOfExecutingTheGeneratedSqlAgain() {
        when(agent.ask(anyString(), any(), any())).thenReturn(answer(rows(BigDecimal.TEN)));
        when(queryExecutor.execute(GOLDEN_SQL)).thenReturn(golden(rows(BigDecimal.TEN)));

        service.evaluate(question(), admin());

        verify(queryExecutor, times(1)).execute(GOLDEN_SQL);
        verify(queryExecutor, never()).execute(GENERATED_SQL);
    }

    @Test
    void doesNotExecuteGoldenSqlWhenTheAgentFailed() {
        when(agent.ask(anyString(), any(), any())).thenReturn(failedAnswer());

        EvaluationService.CaseResult result = service.evaluate(question(), admin());

        assertThat(result.passed()).isFalse();
        assertThat(result.code()).isEqualTo("GENERATION_FAILED");
        verifyNoInteractions(queryExecutor);
    }

    @Test
    void rejectsNonAdminEvaluationRuns() {
        AppUser nonAdmin = user("zhangsan", false);
        when(currentUserService.resolve("zhangsan")).thenReturn(nonAdmin);
        doThrow(ChatBiException.forbidden("ADMIN_REQUIRED", "该操作需要平台管理员权限"))
                .when(currentUserService).requireAdmin(nonAdmin);

        assertThatThrownBy(() -> service.start(null, 1, "zhangsan"))
                .isInstanceOf(ChatBiException.class)
                .satisfies(error -> assertThat(((ChatBiException) error).getCode())
                        .isEqualTo("ADMIN_REQUIRED"));
    }

    private EvaluationService.EvalQuestion question() {
        return new EvaluationService.EvalQuestion(1, "sales", "easy", "今年销售额", "OK",
                GOLDEN_SQL, List.of("v_sales_fact"));
    }

    private QueryExecutor.QueryExecution rows(BigDecimal value) {
        return new QueryExecutor.QueryExecution(true,
                List.of(new QueryExecutor.ResultColumn("total", "numeric")),
                List.of(Map.of("total", value)), true, false, 1, null);
    }

    private QueryExecutor.QueryExecution golden(QueryExecutor.QueryExecution source) {
        return new QueryExecutor.QueryExecution(true, source.columns(), source.rows(),
                true, false, source.elapsedMs(), null);
    }

    private AgentResult answer(QueryExecutor.QueryExecution execution) {
        return new AgentResult("OK", "今年销售额", "AI", GENERATED_SQL, GENERATED_SQL, "说明", 0.9,
                null, AgentResult.GuardInfo.empty(),
                new AgentResult.QueryResult(execution.columns(), execution.rows(), false,
                        execution.rows().size()),
                AgentResult.Usage.empty(),
                new AgentResult.Performance(0, 0, 0, 0, 1, null, List.of()),
                AgentResult.SchemaInfo.empty(), new AgentResult.Failure(null, null, null),
                List.of(), false, null);
    }

    private AgentResult failedAnswer() {
        return new AgentResult("ERROR", "今年销售额", "AI", null, null, null, 0,
                null, AgentResult.GuardInfo.empty(), AgentResult.QueryResult.empty(),
                AgentResult.Usage.empty(),
                new AgentResult.Performance(0, 0, 0, 0, 1, null, List.of()),
                AgentResult.SchemaInfo.empty(),
                new AgentResult.Failure("generate", "GENERATION_FAILED", "模型调用失败"),
                List.of(), false, null);
    }

    private AppUser admin() {
        return user("lisi", true);
    }

    private AppUser user(String username, boolean admin) {
        AppUser user = new AppUser();
        user.setUsername(username);
        user.setIsAdmin(admin);
        user.setEnabled(true);
        return user;
    }
}
