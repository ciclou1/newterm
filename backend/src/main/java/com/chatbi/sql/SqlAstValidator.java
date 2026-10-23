package com.chatbi.sql;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.WithItem;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Allow-list-first SQL validator. It parses the statement, restricts it to a
 * single read-only SELECT tree over semantic views, rejects dangerous
 * primitives and returns an AST-normalized SQL string.
 */
@Component
public class SqlAstValidator {

    private static final int MAX_SQL_LENGTH = 8_000;
    private static final Pattern DANGEROUS_FUNCTION = Pattern.compile(
            "\\b(pg_sleep|pg_read_file|pg_read_binary_file|pg_ls_dir|lo_import|lo_export|dblink|" +
                    "set_config|current_setting|pg_terminate_backend|pg_cancel_backend|query_to_xml|" +
                    "txid_current|pg_stat_file|generate_series|copy)\\s*\\(",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SYSTEM_SCHEMA = Pattern.compile(
            "\\b(information_schema|pg_catalog|pg_toast)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern NESTED_SELECT = Pattern.compile("\\(\\s*select\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern DML = Pattern.compile(
            "\\b(insert|update|delete|merge|drop|alter|create|truncate|grant|revoke|call|vacuum|analyze|" +
                    "refresh|comment|security|copy)\\b", Pattern.CASE_INSENSITIVE);

    public Validation validate(String rawSql, Set<String> allowedTables) {
        List<GuardCheck> checks = new ArrayList<>();
        checks.add(new GuardCheck("statement-present", rawSql != null && !rawSql.isBlank(),
                rawSql == null ? "SQL 为空" : "已接收到 SQL"));
        if (rawSql == null || rawSql.isBlank()) {
            return Validation.rejected("EMPTY_SQL", "模型未返回 SQL", checks);
        }
        String sql = rawSql.trim();
        if (sql.length() > MAX_SQL_LENGTH) {
            checks.add(new GuardCheck("length", false, "SQL 超过 " + MAX_SQL_LENGTH + " 字符"));
            return Validation.rejected("SQL_TOO_LONG", "SQL 过长，已拒绝执行", checks);
        }
        checks.add(new GuardCheck("length", true, "SQL 长度 " + sql.length()));

        String probe = stripTrailingSemicolon(sql);
        if (probe.contains(";")) {
            checks.add(new GuardCheck("single-statement", false, "检测到多条语句"));
            return Validation.rejected("MULTI_STATEMENT", "只允许单条 SELECT 语句", checks);
        }
        checks.add(new GuardCheck("single-statement", true, "单条语句"));

        if (NESTED_SELECT.matcher(probe).find()) {
            checks.add(new GuardCheck("single-level-select", false, "检测到子查询 / CTE；当前策略只允许单层 SELECT"));
            return Validation.rejected("NESTED_QUERY", "当前安全策略只允许单层 SELECT，请改写为不使用子查询或 CTE 的查询", checks);
        }
        checks.add(new GuardCheck("single-level-select", true, "单层 SELECT"));

        if (SYSTEM_SCHEMA.matcher(probe).find()) {
            checks.add(new GuardCheck("system-schema", false, "包含系统 schema"));
            return Validation.rejected("SYSTEM_SCHEMA", "禁止访问系统 schema", checks);
        }
        if (DANGEROUS_FUNCTION.matcher(probe).find()) {
            checks.add(new GuardCheck("dangerous-function", false, "包含危险函数"));
            return Validation.rejected("DANGEROUS_FUNCTION", "SQL 中包含不允许的函数", checks);
        }
        checks.add(new GuardCheck("dangerous-function", true, "未发现危险函数"));

        String dmlWord = firstMatch(DML, probe);
        if (dmlWord != null) {
            checks.add(new GuardCheck("read-only", false, "包含写操作关键字：" + dmlWord));
            return Validation.rejected("WRITE_KEYWORD", "仅支持查询，禁止 DDL/DML 与数据库管理语句", checks);
        }

        Statement statement;
        try {
            statement = CCJSqlParserUtil.parse(probe);
        } catch (JSQLParserException e) {
            checks.add(new GuardCheck("parse", false, "SQL 语法解析失败：" + shortMessage(e)));
            return Validation.rejected("PARSE_ERROR", "SQL 语法解析失败：" + shortMessage(e), checks);
        }
        if (!(statement instanceof Select select)) {
            checks.add(new GuardCheck("select-only", false, "语句类型为 " + statement.getClass().getSimpleName()));
            return Validation.rejected("NOT_SELECT", "只允许执行 SELECT 查询", checks);
        }
        checks.add(new GuardCheck("select-only", true, "SELECT"));

        List<WithItem> withItems = select.getWithItemsList();
        if (withItems != null && !withItems.isEmpty()) {
            checks.add(new GuardCheck("no-cte", false, "包含 WITH 子句"));
            return Validation.rejected("NESTED_QUERY", "当前安全策略不允许 CTE，请改写为单层 SELECT", checks);
        }

        List<String> tables = new ArrayList<>();
        try {
            collectSelect(select, allowedTables, checks, tables);
        } catch (PolicyException e) {
            return Validation.rejected(e.code, e.getMessage(), checks);
        }

        if (tables.isEmpty()) {
            checks.add(new GuardCheck("table-whitelist", false, "未识别到任何表"));
            return Validation.rejected("NO_TABLE", "查询必须引用语义层中的表", checks);
        }
        checks.add(new GuardCheck("table-whitelist", true, "白名单表：" + String.join(", ", new LinkedHashSet<>(tables))));

        return new Validation(true, select.toString(), null, null,
                List.copyOf(new LinkedHashSet<>(tables)), checks);
    }

    private void collectSelect(Select select, Set<String> allowedTables, List<GuardCheck> checks,
                               List<String> tables) throws PolicyException {
        if (select instanceof PlainSelect plain) {
            if (plain.getIntoTables() != null && !plain.getIntoTables().isEmpty()) {
                throw new PolicyException("SELECT_INTO", "禁止 SELECT INTO");
            }
            if (plain.getForUpdateTable() != null) {
                throw new PolicyException("LOCKING", "禁止 SELECT ... FOR UPDATE");
            }
            collectFromItem(plain.getFromItem(), allowedTables, tables);
            if (plain.getJoins() != null) {
                for (Join join : plain.getJoins()) {
                    collectFromItem(join.getRightItem(), allowedTables, tables);
                }
            }
            if (plain.getWhere() != null && plain.getWhere().toString().toLowerCase(Locale.ROOT).contains("select")) {
                throw new PolicyException("NESTED_QUERY", "当前安全策略只允许单层 SELECT");
            }
        } else if (select instanceof SetOperationList) {
            // The product policy is a single-level, single-branch SELECT. UNION /
            // INTERSECT / EXCEPT change result semantics and make row-level
            // auditing and permission reasoning harder, so they are rejected
            // rather than silently allowed.
            throw new PolicyException("SET_OPERATION",
                    "不允许 UNION / INTERSECT / EXCEPT，请改写为单层 SELECT");
        } else if (select instanceof ParenthesedSelect) {
            throw new PolicyException("NESTED_QUERY", "当前安全策略只允许单层 SELECT");
        } else {
            throw new PolicyException("UNSUPPORTED_SELECT", "不支持的查询形态");
        }
    }

    private void collectFromItem(FromItem item, Set<String> allowedTables, List<String> tables)
            throws PolicyException {
        if (item == null) {
            throw new PolicyException("NO_FROM", "查询缺少 FROM 子句");
        }
        if (item instanceof ParenthesedSelect) {
            throw new PolicyException("NESTED_QUERY", "当前安全策略只允许单层 SELECT");
        }
        if (!(item instanceof Table table)) {
            throw new PolicyException("UNSUPPORTED_FROM", "只允许查询普通表/视图，不支持表函数或嵌套查询");
        }
        String schema = table.getSchemaName();
        if (schema != null && !schema.isBlank() && !"public".equalsIgnoreCase(schema)) {
            throw new PolicyException("SCHEMA_DENIED", "只允许访问 public schema");
        }
        String name = table.getName().toLowerCase(Locale.ROOT);
        if (!allowedTables.contains(name)) {
            throw new PolicyException("TABLE_DENIED", "表 " + name + " 不在语义层白名单中");
        }
        tables.add(name);
    }

    private static String stripTrailingSemicolon(String sql) {
        String result = sql.trim();
        while (result.endsWith(";")) {
            result = result.substring(0, result.length() - 1).trim();
        }
        return result;
    }

    private static String firstMatch(Pattern pattern, String sql) {
        var matcher = pattern.matcher(sql);
        return matcher.find() ? matcher.group(1).toLowerCase(Locale.ROOT) : null;
    }

    private static String shortMessage(Exception e) {
        String message = e.getMessage();
        if (message == null) {
            return "invalid SQL";
        }
        int newline = message.indexOf('\n');
        return newline > 0 ? message.substring(0, newline) : message;
    }

    public record Validation(boolean passed, String sql, String code, String message,
                             List<String> tables, List<GuardCheck> checks) {
        static Validation rejected(String code, String message, List<GuardCheck> checks) {
            return new Validation(false, null, code, message, List.of(), List.copyOf(checks));
        }
    }

    private static final class PolicyException extends Exception {
        private final String code;

        private PolicyException(String code, String message) {
            super(message);
            this.code = code;
        }
    }
}
