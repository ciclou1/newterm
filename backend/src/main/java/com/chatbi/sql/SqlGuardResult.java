package com.chatbi.sql;

import java.util.List;

/**
 * Result of parsing + policy checks. A rejected result never reaches the
 * database. Passing SQL is normalized from the AST, not copied from model text.
 */
public record SqlGuardResult(
        boolean passed,
        String sql,
        String code,
        String message,
        List<String> tables,
        List<GuardCheck> checks,
        List<RowScope> rowScopes,
        boolean permissionDenied) {

    public static SqlGuardResult rejected(String code, String message, List<GuardCheck> checks) {
        return new SqlGuardResult(false, null, code, message, List.of(), checks, List.of(), false);
    }

    public static SqlGuardResult permissionDenied(String message, List<GuardCheck> checks) {
        return new SqlGuardResult(false, null, "ROW_LEVEL_DENIED", message, List.of(), checks, List.of(), true);
    }

    public record RowScope(String table, String predicate) {
    }
}
