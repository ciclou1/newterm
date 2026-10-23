package com.chatbi.web;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chatbi.common.ChatBiException;
import com.chatbi.domain.entity.FewShotExample;
import com.chatbi.domain.entity.RowPermission;
import com.chatbi.domain.entity.SemEnum;
import com.chatbi.domain.entity.SemMetric;
import com.chatbi.domain.entity.SemSynonym;
import com.chatbi.domain.entity.SemTable;
import com.chatbi.mapper.FewShotExampleMapper;
import com.chatbi.mapper.RowPermissionMapper;
import com.chatbi.mapper.SemEnumMapper;
import com.chatbi.mapper.SemMetricMapper;
import com.chatbi.mapper.SemSynonymMapper;
import com.chatbi.semantic.SemanticCatalog;
import com.chatbi.semantic.SemanticIndexer;
import com.chatbi.semantic.SemanticSnapshot;
import com.chatbi.service.CurrentUserService;
import com.chatbi.sql.RowPermissionInjector;
import com.chatbi.sql.SqlAstValidator;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Slf4j
@RestController
@RequestMapping("/api/semantic")
public class SemanticController {

    private final SemanticCatalog catalog;
    private final SemanticIndexer indexer;
    private final CurrentUserService currentUserService;
    private final SemMetricMapper metricMapper;
    private final SemSynonymMapper synonymMapper;
    private final SemEnumMapper enumMapper;
    private final FewShotExampleMapper exampleMapper;
    private final RowPermissionMapper permissionMapper;
    private final SqlAstValidator validator;

    public SemanticController(SemanticCatalog catalog, SemanticIndexer indexer,
                              CurrentUserService currentUserService, SemMetricMapper metricMapper,
                              SemSynonymMapper synonymMapper, SemEnumMapper enumMapper,
                              FewShotExampleMapper exampleMapper, RowPermissionMapper permissionMapper,
                              SqlAstValidator validator) {
        this.catalog = catalog;
        this.indexer = indexer;
        this.currentUserService = currentUserService;
        this.metricMapper = metricMapper;
        this.synonymMapper = synonymMapper;
        this.enumMapper = enumMapper;
        this.exampleMapper = exampleMapper;
        this.permissionMapper = permissionMapper;
        this.validator = validator;
    }

    @GetMapping("/overview")
    public Overview overview() {
        SemanticSnapshot snapshot = catalog.snapshot();
        List<TableSummary> tables = new ArrayList<>();
        for (SemTable table : snapshot.tables()) {
            long metricCount = snapshot.metrics().stream()
                    .filter(m -> table.getTableName().equals(m.getBaseTable())).count();
            tables.add(new TableSummary(table.getTableName(), table.getBusinessName(), table.getDomain(),
                    table.getDescription(), snapshot.columnsOf(table.getTableName()).size(),
                    (int) metricCount, Boolean.TRUE.equals(table.getEnabled())));
        }
        return new Overview(snapshot.version(), snapshot.tables().size(), snapshot.columns().size(),
                snapshot.metrics().size(), snapshot.enums().size(), snapshot.synonyms().size(),
                snapshot.examples().size(), snapshot.rowPermissions().size(), tables);
    }

    @GetMapping("/tables")
    public List<TableDetail> tables() {
        SemanticSnapshot snapshot = catalog.snapshot();
        return snapshot.tables().stream()
                .map(t -> new TableDetail(t, snapshot.columnsOf(t.getTableName()),
                        snapshot.synonymsFor("TABLE", t.getTableName()),
                        snapshot.enumsOf(t.getTableName())))
                .toList();
    }

    @GetMapping("/metrics")
    public List<SemMetric> metrics() {
        return catalog.snapshot().metrics();
    }

    @GetMapping("/synonyms")
    public List<SemSynonym> synonyms() {
        return catalog.snapshot().synonyms();
    }

    @GetMapping("/enums")
    public List<SemEnum> enums() {
        return enumMapper.selectList(new LambdaQueryWrapper<SemEnum>()
                .orderByAsc(SemEnum::getTableName).orderByAsc(SemEnum::getColumnName)
                .orderByAsc(SemEnum::getEnumLabel));
    }

    @GetMapping("/examples")
    public List<FewShotExample> examples() {
        return exampleMapper.selectList(new LambdaQueryWrapper<FewShotExample>()
                .orderByDesc(FewShotExample::getId));
    }

    @GetMapping("/permissions")
    public List<RowPermission> permissions() {
        currentUserService.requireAdmin();
        return permissionMapper.selectList(new LambdaQueryWrapper<RowPermission>()
                .orderByAsc(RowPermission::getUserId));
    }

    @PostMapping("/metrics")
    public SemMetric saveMetric(@Valid @RequestBody MetricRequest request) {
        currentUserService.requireAdmin();
        SemanticSnapshot snapshot = catalog.snapshot();
        if (snapshot.table(request.baseTable()).isEmpty()) {
            throw ChatBiException.badRequest("INVALID_BASE_TABLE", "指标所属表不在语义层中");
        }
        Set<String> allowed = new LinkedHashSet<>(snapshot.enabledTableNames());
        SqlAstValidator.Validation validation = validator.validate(
                "SELECT " + request.expression() + " FROM " + request.baseTable(), allowed);
        if (!validation.passed()) {
            throw ChatBiException.badRequest("INVALID_METRIC_EXPRESSION",
                    "指标表达式未通过安全校验：" + validation.message());
        }

        SemMetric metric = metricMapper.selectOne(new LambdaQueryWrapper<SemMetric>()
                .eq(SemMetric::getMetricCode, request.metricCode()).last("LIMIT 1"));
        if (metric == null) {
            metric = new SemMetric();
            metric.setMetricCode(request.metricCode());
        }
        metric.setMetricName(request.metricName());
        metric.setDescription(request.description());
        metric.setExpression(request.expression());
        metric.setBaseTable(request.baseTable());
        metric.setTimeColumn(blankToNull(request.timeColumn()));
        metric.setUnit(request.unit());
        metric.setValueFormat(request.valueFormat() == null ? "number" : request.valueFormat());
        metric.setSynonymsText(request.synonymsText());
        metric.setEnabled(request.enabled() == null || request.enabled());
        metric.setUpdatedAt(OffsetDateTime.now());
        if (metric.getId() == null) {
            metricMapper.insert(metric);
        } else {
            metricMapper.updateById(metric);
        }
        reload();
        return metric;
    }

    @PostMapping("/synonyms")
    public SemSynonym saveSynonym(@Valid @RequestBody SynonymRequest request) {
        currentUserService.requireAdmin();
        SemSynonym synonym = synonymMapper.selectOne(new LambdaQueryWrapper<SemSynonym>()
                .eq(SemSynonym::getTargetType, request.targetType())
                .eq(SemSynonym::getTargetKey, request.targetKey())
                .eq(SemSynonym::getSynonym, request.synonym()).last("LIMIT 1"));
        if (synonym == null) {
            synonym = new SemSynonym();
            synonym.setTargetType(request.targetType());
            synonym.setTargetKey(request.targetKey());
            synonym.setSynonym(request.synonym());
        }
        synonym.setWeight(request.weight() == null ? BigDecimal.ONE : request.weight());
        if (synonym.getId() == null) {
            synonymMapper.insert(synonym);
        } else {
            synonymMapper.updateById(synonym);
        }
        reload();
        return synonym;
    }

    @PostMapping("/examples")
    public FewShotExample saveExample(@Valid @RequestBody ExampleRequest request) {
        currentUserService.requireAdmin();
        SemanticSnapshot snapshot = catalog.snapshot();
        SqlAstValidator.Validation validation = validator.validate(request.sqlText(),
                new LinkedHashSet<>(snapshot.enabledTableNames()));
        if (!validation.passed()) {
            throw ChatBiException.badRequest("INVALID_EXAMPLE_SQL",
                    "示例 SQL 未通过安全校验：" + validation.message());
        }
        FewShotExample example = request.id() == null ? new FewShotExample()
                : exampleMapper.selectById(request.id());
        if (example == null) {
            throw ChatBiException.notFound("示例不存在");
        }
        example.setQuestion(request.question());
        example.setSqlText(validation.sql());
        example.setTablesUsed(request.tablesUsed());
        example.setTags(request.tags());
        example.setEnabled(request.enabled() == null || request.enabled());
        if (example.getId() == null) {
            exampleMapper.insert(example);
        } else {
            exampleMapper.updateById(example);
        }
        reload();
        return example;
    }

    @PostMapping("/permissions")
    public RowPermission savePermission(@Valid @RequestBody PermissionRequest request) {
        currentUserService.requireAdmin();
        if (catalog.snapshot().table(request.tableName()).isEmpty()) {
            throw ChatBiException.badRequest("INVALID_TABLE", "表不在语义层中");
        }
        String column = request.columnName() == null
                ? null : request.columnName().trim().toLowerCase(Locale.ROOT);
        if (!RowPermissionInjector.isKnownColumn(catalog.snapshot(), request.tableName(), column)) {
            throw ChatBiException.badRequest("INVALID_PERMISSION_COLUMN",
                    "行级权限列必须是语义层已注册的字段名（小写标识符）：" + request.columnName());
        }
        RowPermission permission = request.id() == null ? new RowPermission()
                : permissionMapper.selectById(request.id());
        if (permission == null) {
            throw ChatBiException.notFound("行权限规则不存在");
        }
        permission.setUserId(request.userId());
        permission.setTableName(request.tableName());
        permission.setColumnName(column);
        permission.setAllowedValues(request.allowedValues());
        permission.setDescription(request.description());
        permission.setEnabled(request.enabled() == null || request.enabled());
        if (permission.getId() == null) {
            permissionMapper.insert(permission);
        } else {
            permissionMapper.updateById(permission);
        }
        reload();
        return permission;
    }

    @PostMapping("/refresh")
    public Map<String, Object> refresh() {
        currentUserService.requireAdmin();
        SemanticSnapshot snapshot = catalog.refresh();
        SemanticIndexer.IndexStats stats = indexer.reindexAll();
        return Map.of("version", snapshot.version(), "documents", stats.documents(),
                "upserted", stats.upserted(), "unchanged", stats.unchanged());
    }

    private void reload() {
        catalog.refresh();
        try {
            indexer.reindexAll();
        } catch (Exception e) {
            log.warn("Semantic reindex skipped after save: {}", e.getMessage());
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    public record Overview(long version, int tableCount, int columnCount, int metricCount,
                           int enumCount, int synonymCount, int exampleCount, int permissionCount,
                           List<TableSummary> tables) {
    }

    public record TableSummary(String tableName, String businessName, String domain, String description,
                               int columnCount, int metricCount, boolean enabled) {
    }

    public record TableDetail(SemTable table, List<com.chatbi.domain.entity.SemColumn> columns,
                              List<SemSynonym> synonyms, List<SemEnum> enums) {
    }

    public record MetricRequest(@NotBlank @Size(max = 64) String metricCode,
                                @NotBlank @Size(max = 64) String metricName,
                                @Size(max = 500) String description,
                                @NotBlank @Size(max = 1000) String expression,
                                @NotBlank @Size(max = 64) String baseTable,
                                @Size(max = 64) String timeColumn,
                                @Size(max = 16) String unit,
                                @Size(max = 16) String valueFormat,
                                @Size(max = 500) String synonymsText,
                                Boolean enabled) {
    }

    public record SynonymRequest(@NotBlank @Size(max = 16) String targetType,
                                 @NotBlank @Size(max = 128) String targetKey,
                                 @NotBlank @Size(max = 64) String synonym,
                                 BigDecimal weight) {
    }

    public record ExampleRequest(Long id,
                                 @NotBlank @Size(max = 500) String question,
                                 @NotBlank @Size(max = 4000) String sqlText,
                                 @Size(max = 500) String tablesUsed,
                                 @Size(max = 200) String tags,
                                 Boolean enabled) {
    }

    public record PermissionRequest(Long id,
                                    @NotBlank @Size(max = 64) String userId,
                                    @NotBlank @Size(max = 64) String tableName,
                                    @NotBlank @Size(max = 64) String columnName,
                                    @NotBlank @Size(max = 1000) String allowedValues,
                                    @Size(max = 200) String description,
                                    Boolean enabled) {
    }
}
