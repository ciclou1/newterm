package com.chatbi.sql;

import com.chatbi.domain.entity.AppUser;
import com.chatbi.domain.entity.RowPermission;
import com.chatbi.semantic.SemanticSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RowPermissionInjectorTest {

    private final RowPermissionInjector injector = new RowPermissionInjector();

    @Test
    void injectsTheConfiguredDepartmentPredicate() {
        SemanticSnapshot snapshot = snapshot(rule("zhangsan", "1, 2"));

        RowPermissionInjector.Injection result = injector.inject(
                "SELECT region, SUM(sales_amount) AS total FROM v_sales_fact GROUP BY region",
                user("zhangsan", false), snapshot);

        assertThat(result.allowed()).isTrue();
        assertThat(result.sql()).contains("v_sales_fact.department_id IN (1, 2)");
        assertThat(result.scopes()).hasSize(1);
        assertThat(result.scopes().getFirst().table()).isEqualTo("v_sales_fact");
        assertThat(result.scopes().getFirst().predicate()).contains("department_id IN (1, 2)");
    }

    @Test
    void quotesNonNumericPermissionValues() {
        SemanticSnapshot snapshot = snapshot(rule("zhangsan", "华东,华南"));

        RowPermissionInjector.Injection result = injector.inject(
                "SELECT * FROM v_sales_fact", user("zhangsan", false), snapshot);

        assertThat(result.allowed()).isTrue();
        assertThat(result.sql()).contains("department_id IN ('华东', '华南')");
    }

    @Test
    void deniesAUserWithNoRuleForAProtectedTable() {
        SemanticSnapshot snapshot = snapshot(rule("zhangsan", "1"));

        RowPermissionInjector.Injection result = injector.inject(
                "SELECT * FROM v_sales_fact", user("wangwu", false), snapshot);

        assertThat(result.allowed()).isFalse();
        assertThat(result.code()).isEqualTo("ROW_LEVEL_DENIED");
        assertThat(result.sql()).isNull();
    }

    @Test
    void deniesWhenAnEnabledRuleHasNoValues() {
        SemanticSnapshot snapshot = snapshot(rule("zhangsan", " "));

        RowPermissionInjector.Injection result = injector.inject(
                "SELECT * FROM v_sales_fact", user("zhangsan", false), snapshot);

        assertThat(result.allowed()).isFalse();
        assertThat(result.code()).isEqualTo("ROW_LEVEL_DENIED");
    }

    @Test
    void leavesAdminQueriesUntouched() {
        SemanticSnapshot snapshot = snapshot(rule("zhangsan", "1"));
        String sql = "SELECT * FROM v_sales_fact";

        RowPermissionInjector.Injection result = injector.inject(sql, user("lisi", true), snapshot);

        assertThat(result.allowed()).isTrue();
        assertThat(result.sql()).isEqualTo(sql);
        assertThat(result.scopes()).isEmpty();
    }

    @Test
    void skipsTablesWithoutAnyRule() {
        SemanticSnapshot snapshot = snapshot(rule("zhangsan", "1"));
        String sql = "SELECT * FROM v_purchase_fact";

        RowPermissionInjector.Injection result = injector.inject(sql, user("wangwu", false), snapshot);

        assertThat(result.allowed()).isTrue();
        assertThat(result.scopes()).isEmpty();
    }

    @Test
    void combinesMultipleRulesWithAnd() {
        SemanticSnapshot snapshot = snapshot(
                rule("zhangsan", "1"),
                rule("zhangsan", "2025", "fiscal_year"));

        RowPermissionInjector.Injection result = injector.inject(
                "SELECT * FROM v_sales_fact", user("zhangsan", false), snapshot);

        assertThat(result.allowed()).isTrue();
        assertThat(result.sql()).contains("department_id IN (1)");
        assertThat(result.sql()).contains("fiscal_year IN (2025)");
        assertThat(result.scopes()).hasSize(1);
        assertThat(result.scopes().getFirst().predicate())
                .contains("department_id IN (1)")
                .contains("fiscal_year IN (2025)");
    }

    private SemanticSnapshot snapshot(RowPermission... permissions) {
        return new SemanticSnapshot(1, List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(permissions));
    }

    private RowPermission rule(String username, String values) {
        return rule(username, values, "department_id");
    }

    private RowPermission rule(String username, String values, String column) {
        RowPermission permission = new RowPermission();
        permission.setId(1L);
        permission.setUserId(username);
        permission.setTableName("v_sales_fact");
        permission.setColumnName(column);
        permission.setAllowedValues(values);
        permission.setEnabled(true);
        return permission;
    }

    private AppUser user(String username, boolean admin) {
        AppUser user = new AppUser();
        user.setUsername(username);
        user.setRole(admin ? "ADMIN" : "ANALYST");
        user.setIsAdmin(admin);
        user.setEnabled(true);
        return user;
    }
}
