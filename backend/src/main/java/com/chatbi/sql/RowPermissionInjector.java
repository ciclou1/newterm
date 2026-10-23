package com.chatbi.sql;

import com.chatbi.domain.entity.AppUser;
import com.chatbi.domain.entity.RowPermission;
import com.chatbi.semantic.SemanticSnapshot;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Parenthesis;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SetOperationList;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Injects user row predicates into the parsed AST. Predicates are built from
 * admin-managed metadata (not model output) and are applied to every physical
 * table reference in the top-level SELECT tree.
 */
@Component
public class RowPermissionInjector {

    private static final Pattern NUMERIC = Pattern.compile("-?\\d+(\\.\\d+)?");

    public Injection inject(String sql, AppUser user, SemanticSnapshot snapshot) {
        if (Boolean.TRUE.equals(user.getIsAdmin())) {
            return Injection.allowed(sql, List.of());
        }
        try {
            Statement statement = CCJSqlParserUtil.parse(sql);
            if (!(statement instanceof Select select)) {
                return Injection.denied("FAILED", "无法解析查询");
            }
            Map<String, List<RowPermission>> globalRules = new LinkedHashMap<>();
            for (RowPermission permission : snapshot.rowPermissions()) {
                if (Boolean.TRUE.equals(permission.getEnabled())) {
                    globalRules.computeIfAbsent(permission.getTableName().toLowerCase(Locale.ROOT), k -> new ArrayList<>())
                            .add(permission);
                }
            }

            List<SqlGuardResult.RowScope> scopes = new ArrayList<>();
            InjectionError error = apply(select, user, globalRules, scopes);
            if (error != null) {
                return Injection.denied(error.code, error.message);
            }
            return Injection.allowed(select.toString(), List.copyOf(new LinkedHashSet<>(scopes)));
        } catch (JSQLParserException e) {
            return Injection.denied("PARSE_ERROR", "行级权限注入失败：SQL 无法解析");
        }
    }

    private InjectionError apply(Select select, AppUser user,
                                 Map<String, List<RowPermission>> globalRules,
                                 List<SqlGuardResult.RowScope> scopes) {
        if (select instanceof PlainSelect plain) {
            List<Table> tables = new ArrayList<>();
            addTable(plain.getFromItem(), tables);
            if (plain.getJoins() != null) {
                for (Join join : plain.getJoins()) {
                    addTable(join.getRightItem(), tables);
                }
            }
            for (Table table : tables) {
                String tableName = table.getName().toLowerCase(Locale.ROOT);
                List<RowPermission> allForTable = globalRules.get(tableName);
                if (allForTable == null || allForTable.isEmpty()) {
                    continue;
                }
                List<RowPermission> mine = allForTable.stream()
                        .filter(p -> user.getUsername().equals(p.getUserId()))
                        .toList();
                if (mine.isEmpty()) {
                    return new InjectionError("ROW_LEVEL_DENIED",
                            "当前账号无权访问表 " + tableName + " 的数据");
                }
                String qualifier = table.getAlias() != null ? table.getAlias().getName() : table.getName();
                List<String> predicates = new ArrayList<>();
                for (RowPermission permission : mine) {
                    String predicate = predicate(qualifier, permission);
                    if (predicate == null) {
                        return new InjectionError("ROW_LEVEL_DENIED",
                                "行级权限配置为空，已拒绝访问 " + tableName);
                    }
                    predicates.add(predicate);
                }
                String combined = String.join(" AND ", predicates);
                try {
                    Expression injected = CCJSqlParserUtil.parseCondExpression(combined);
                    Expression where = plain.getWhere();
                    plain.setWhere(where == null
                            ? new Parenthesis(injected)
                            : new AndExpression(where, new Parenthesis(injected)));
                    scopes.add(new SqlGuardResult.RowScope(tableName, combined));
                } catch (JSQLParserException e) {
                    return new InjectionError("ROW_LEVEL_DENIED", "行级权限表达式无效，已拒绝执行");
                }
            }
            return null;
        }
        if (select instanceof SetOperationList union) {
            for (Select child : union.getSelects()) {
                InjectionError error = apply(child, user, globalRules, scopes);
                if (error != null) {
                    return error;
                }
            }
            return null;
        }
        return new InjectionError("ROW_LEVEL_DENIED", "无法对当前查询形态应用行级权限");
    }

    private void addTable(FromItem item, List<Table> tables) {
        if (item instanceof Table table) {
            tables.add(table);
        }
    }

    private String predicate(String qualifier, RowPermission permission) {
        if (permission.getAllowedValues() == null || permission.getAllowedValues().isBlank()) {
            return null;
        }
        String values = java.util.Arrays.stream(permission.getAllowedValues().split("[,，;]"))
                .map(String::trim)
                .filter(v -> !v.isBlank())
                .map(v -> NUMERIC.matcher(v).matches() ? v : "'" + v.replace("'", "''") + "'")
                .collect(java.util.stream.Collectors.joining(", "));
        if (values.isBlank()) {
            return null;
        }
        return qualifier + "." + permission.getColumnName() + " IN (" + values + ")";
    }

    public record Injection(boolean allowed, String sql, String code, String message,
                            List<SqlGuardResult.RowScope> scopes) {
        static Injection allowed(String sql, List<SqlGuardResult.RowScope> scopes) {
            return new Injection(true, sql, null, null, scopes);
        }

        static Injection denied(String code, String message) {
            return new Injection(false, null, code, message, List.of());
        }
    }

    private record InjectionError(String code, String message) {
    }
}
