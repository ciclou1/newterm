package com.chatbi.sql;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SqlAstValidatorTest {

    private final SqlAstValidator validator = new SqlAstValidator();
    private final Set<String> allowed = Set.of("v_sales_fact", "v_purchase_fact");

    @Test
    void acceptsASingleLevelSelectOverAWhitelistedView() {
        SqlAstValidator.Validation result = validator.validate(
                "SELECT region, SUM(sales_amount) AS total FROM v_sales_fact " +
                        "WHERE region = '华东' GROUP BY region ORDER BY total DESC", allowed);

        assertThat(result.passed()).isTrue();
        assertThat(result.code()).isNull();
        assertThat(result.sql()).isNotBlank();
        assertThat(result.tables()).containsExactly("v_sales_fact");
        assertThat(result.checks()).isNotEmpty();
    }

    @Test
    void acceptsJoinsWhenEveryTableIsAllowed() {
        SqlAstValidator.Validation result = validator.validate(
                "SELECT s.region, SUM(s.sales_amount) FROM v_sales_fact s " +
                        "JOIN v_purchase_fact p ON p.region = s.region GROUP BY s.region", allowed);

        assertThat(result.passed()).isTrue();
        assertThat(result.tables()).containsExactly("v_sales_fact", "v_purchase_fact");
    }

    @Test
    void rejectsEmptySql() {
        assertRejected("  ", "EMPTY_SQL");
        assertRejected(null, "EMPTY_SQL");
    }

    @Test
    void rejectsMultipleStatements() {
        assertRejected("SELECT * FROM v_sales_fact; SELECT * FROM v_purchase_fact", "MULTI_STATEMENT");
    }

    @Test
    void rejectsDdlAndDmlKeywords() {
        assertRejected("DELETE FROM v_sales_fact", "WRITE_KEYWORD");
        assertRejected("UPDATE v_sales_fact SET region = 'x'", "WRITE_KEYWORD");
        assertRejected("DROP TABLE v_sales_fact", "WRITE_KEYWORD");
    }

    @Test
    void rejectsNestedSelectsAndCtes() {
        assertRejected("SELECT * FROM v_sales_fact WHERE region IN " +
                "(SELECT region FROM v_purchase_fact)", "NESTED_QUERY");
        assertRejected("WITH x AS (SELECT * FROM v_sales_fact) SELECT * FROM x", "NESTED_QUERY");
    }

    @Test
    void rejectsTablesOutsideTheSemanticLayer() {
        assertRejected("SELECT * FROM app_user", "TABLE_DENIED");
    }

    @Test
    void rejectsSystemSchemasAndForeignSchemas() {
        assertRejected("SELECT * FROM pg_catalog.pg_tables", "SYSTEM_SCHEMA");
        assertRejected("SELECT * FROM other.v_sales_fact", "SCHEMA_DENIED");
    }

    @Test
    void rejectsDangerousFunctions() {
        assertRejected("SELECT pg_sleep(1) FROM v_sales_fact", "DANGEROUS_FUNCTION");
        assertRejected("SELECT pg_read_file('/etc/passwd') FROM v_sales_fact", "DANGEROUS_FUNCTION");
    }

    @Test
    void rejectsSelectIntoAndLockingReads() {
        assertRejected("SELECT * INTO snapshot FROM v_sales_fact", "SELECT_INTO");
        // FOR UPDATE is caught by the earlier write-keyword guard.
        assertRejected("SELECT * FROM v_sales_fact FOR UPDATE", "WRITE_KEYWORD");
    }

    @Test
    void rejectsUnparseableSql() {
        SqlAstValidator.Validation result = validator.validate("SELECT FROM WHERE", allowed);

        assertThat(result.passed()).isFalse();
        assertThat(result.code()).isEqualTo("PARSE_ERROR");
    }

    private void assertRejected(String sql, String code) {
        SqlAstValidator.Validation result = validator.validate(sql, allowed);

        assertThat(result.passed()).as("expected %s to be rejected", sql).isFalse();
        assertThat(result.code()).isEqualTo(code);
        assertThat(result.sql()).isNull();
        assertThat(result.checks()).isNotEmpty();
    }
}
