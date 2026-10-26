package com.chatbi.nl2sql;

import com.chatbi.domain.entity.SemColumn;
import com.chatbi.domain.entity.SemMetric;
import com.chatbi.domain.entity.SemTable;
import com.chatbi.linking.SchemaContext;
import com.chatbi.linking.TextTokenizer;
import com.chatbi.semantic.SemanticCatalog;
import com.chatbi.semantic.SemanticSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DemoSqlGeneratorTest {

    private DemoSqlGenerator generator;

    @BeforeEach
    void setUp() {
        SemanticCatalog catalog = mock(SemanticCatalog.class);
        when(catalog.snapshot()).thenReturn(snapshot());
        generator = new DemoSqlGenerator(catalog, new TextTokenizer());
    }

    @Test
    void keepsThePreviousGroupingForAnEllipticalFollowUp() {
        SqlGeneration first = generator.generate("今年各区域销售额", schema(), null);
        assertThat(first.sql()).isEqualTo(
                "SELECT region, SUM(amount) FILTER (WHERE status = 'COMPLETED') AS sales_amount "
                        + "FROM v_sales_fact WHERE (order_date >= date_trunc('year', CURRENT_DATE)) "
                        + "GROUP BY region ORDER BY sales_amount DESC");

        SqlGeneration followUp = generator.generate("那去年呢", schema(),
                "今年各区域销售额", first.sql());

        assertThat(followUp.sql())
                .isEqualTo("SELECT region, SUM(amount) FILTER (WHERE status = 'COMPLETED') AS sales_amount "
                        + "FROM v_sales_fact WHERE (order_date >= date_trunc('year', CURRENT_DATE) "
                        + "- INTERVAL '1 year' AND order_date < date_trunc('year', CURRENT_DATE)) "
                        + "GROUP BY region ORDER BY sales_amount DESC");
        assertThat(followUp.explanation()).contains("按区域分组").contains("去年");
    }

    @Test
    void recoversTheGroupingFromThePreviousQuestionWhenSqlIsUnavailable() {
        SqlGeneration followUp = generator.generate("那去年呢", schema(), "今年各区域销售额");

        assertThat(followUp.sql()).contains("GROUP BY region")
                .contains("date_trunc('year', CURRENT_DATE) - INTERVAL '1 year'");
    }

    @Test
    void doesNotInheritGroupingWhenTheUserAsksForASingleAggregate() {
        SqlGeneration first = generator.generate("今年各区域销售额", schema(), null);

        SqlGeneration followUp = generator.generate("那今年总额呢", schema(),
                "今年各区域销售额", first.sql());

        assertThat(followUp.sql()).doesNotContain("GROUP BY").doesNotContain("SELECT region,");
    }

    @Test
    void anExplicitDimensionOnTheFollowUpWinsOverTheInheritedOne() {
        SqlGeneration first = generator.generate("今年各区域销售额", schema(), null);

        SqlGeneration followUp = generator.generate("那去年按客户呢", schema(),
                "今年各区域销售额", first.sql());

        assertThat(followUp.sql()).contains("GROUP BY customer_name")
                .doesNotContain("GROUP BY region");
    }

    @Test
    void singleTurnAggregateQuestionsStillReturnOneRow() {
        SqlGeneration generation = generator.generate("去年销售额是多少", schema(), null);

        assertThat(generation.sql()).doesNotContain("GROUP BY");
        assertThat(generation.sql()).contains("date_trunc('year', CURRENT_DATE) - INTERVAL '1 year'");
    }

    @Test
    void ignoresGroupByExpressionsAndFallsBackToTheQuestion() {
        String expressionSql = "SELECT date_trunc('month', order_date) AS m, "
                + "SUM(amount) AS sales_amount FROM v_sales_fact GROUP BY date_trunc('month', order_date)";

        SqlGeneration followUp = generator.generate("那去年呢", schema(), "今年各区域销售额", expressionSql);

        assertThat(followUp.sql()).contains("GROUP BY region");
    }

    private SchemaContext schema() {
        return new SchemaContext(List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), "", 1);
    }

    private SemanticSnapshot snapshot() {
        SemTable table = new SemTable();
        table.setId(1L);
        table.setTableName("v_sales_fact");
        table.setBusinessName("销售明细");
        table.setEnabled(true);

        SemMetric sales = new SemMetric();
        sales.setMetricCode("sales_amount");
        sales.setMetricName("销售额");
        sales.setExpression("SUM(amount) FILTER (WHERE status = 'COMPLETED')");
        sales.setBaseTable("v_sales_fact");
        sales.setTimeColumn("order_date");
        sales.setEnabled(true);

        return new SemanticSnapshot(1, List.of(table),
                List.of(column(1L, "region", "区域", 1),
                        column(1L, "customer_name", "客户名称", 2),
                        column(1L, "order_date", "订单日期", 3),
                        column(1L, "order_month", "订单月份", 4)),
                List.of(sales), List.of(), List.of(), List.of(), List.of());
    }

    private SemColumn column(Long tableId, String name, String businessName, int ordinal) {
        SemColumn column = new SemColumn();
        column.setTableId(tableId);
        column.setColumnName(name);
        column.setBusinessName(businessName);
        column.setDataType("varchar");
        column.setIsDimension(true);
        column.setIsMeasure(false);
        column.setOrdinal(ordinal);
        return column;
    }
}
