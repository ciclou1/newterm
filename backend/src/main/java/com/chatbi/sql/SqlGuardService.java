package com.chatbi.sql;

import com.chatbi.domain.entity.AppUser;
import com.chatbi.semantic.SemanticCatalog;
import com.chatbi.semantic.SemanticSnapshot;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
public class SqlGuardService {

    private final SqlAstValidator validator;
    private final RowPermissionInjector permissionInjector;
    private final SemanticCatalog catalog;

    public SqlGuardService(SqlAstValidator validator,
                           RowPermissionInjector permissionInjector,
                           SemanticCatalog catalog) {
        this.validator = validator;
        this.permissionInjector = permissionInjector;
        this.catalog = catalog;
    }

    public SqlGuardResult guard(String rawSql, AppUser user) {
        SemanticSnapshot snapshot = catalog.snapshot();
        Set<String> allowedTables = new LinkedHashSet<>(snapshot.enabledTableNames());
        SqlAstValidator.Validation validation = validator.validate(rawSql, allowedTables);
        if (!validation.passed()) {
            return SqlGuardResult.rejected(validation.code(), validation.message(), validation.checks());
        }

        RowPermissionInjector.Injection injection = permissionInjector.inject(validation.sql(), user, snapshot);
        List<GuardCheck> checks = new ArrayList<>(validation.checks());
        if (!injection.allowed()) {
            checks.add(new GuardCheck("row-level-permission", false, injection.message()));
            if ("ROW_LEVEL_DENIED".equals(injection.code())) {
                return new SqlGuardResult(false, null, injection.code(), injection.message(), validation.tables(),
                        List.copyOf(checks), List.of(), true);
            }
            return SqlGuardResult.rejected(injection.code(), injection.message(), checks);
        }
        checks.add(new GuardCheck("row-level-permission", true,
                injection.scopes().isEmpty() ? "管理员/无行级规则"
                        : injection.scopes().stream().map(SqlGuardResult.RowScope::predicate)
                        .collect(java.util.stream.Collectors.joining(" ; "))));

        return new SqlGuardResult(true, injection.sql(), null, null, validation.tables(),
                List.copyOf(checks), injection.scopes(), false);
    }
}
