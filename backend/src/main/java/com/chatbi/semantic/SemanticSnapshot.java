package com.chatbi.semantic;

import com.chatbi.domain.entity.FewShotExample;
import com.chatbi.domain.entity.RowPermission;
import com.chatbi.domain.entity.SemColumn;
import com.chatbi.domain.entity.SemEnum;
import com.chatbi.domain.entity.SemMetric;
import com.chatbi.domain.entity.SemSynonym;
import com.chatbi.domain.entity.SemTable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Immutable snapshot of semantic metadata. Refreshing replaces the whole
 * snapshot so readers never see a half-updated catalog.
 */
public final class SemanticSnapshot {

    private final long version;
    private final List<SemTable> tables;
    private final List<SemColumn> columns;
    private final List<SemMetric> metrics;
    private final List<SemSynonym> synonyms;
    private final List<SemEnum> enums;
    private final List<FewShotExample> examples;
    private final List<RowPermission> rowPermissions;

    private final Map<String, SemTable> tableByName;
    private final Map<Long, String> tableNameById;
    private final Map<String, List<SemColumn>> columnsByTable;
    private final Map<String, SemMetric> metricByCode;
    private final Map<String, List<SemSynonym>> synonymsByTarget;
    private final Map<String, List<SemEnum>> enumsByTable;

    public SemanticSnapshot(
            long version,
            List<SemTable> tables,
            List<SemColumn> columns,
            List<SemMetric> metrics,
            List<SemSynonym> synonyms,
            List<SemEnum> enums,
            List<FewShotExample> examples,
            List<RowPermission> rowPermissions) {
        this.version = version;
        this.tables = List.copyOf(tables);
        this.columns = List.copyOf(columns);
        this.metrics = List.copyOf(metrics);
        this.synonyms = List.copyOf(synonyms);
        this.enums = List.copyOf(enums);
        this.examples = List.copyOf(examples);
        this.rowPermissions = List.copyOf(rowPermissions);

        Map<String, SemTable> tbn = new LinkedHashMap<>();
        Map<Long, String> tnbi = new LinkedHashMap<>();
        tables.forEach(t -> {
            tbn.put(t.getTableName(), t);
            tnbi.put(t.getId(), t.getTableName());
        });
        this.tableByName = Map.copyOf(tbn);
        this.tableNameById = Map.copyOf(tnbi);

        Map<String, List<SemColumn>> cbt = new LinkedHashMap<>();
        columns.stream()
                .sorted(Comparator.comparing(SemColumn::getOrdinal, Comparator.nullsLast(Integer::compareTo)))
                .forEach(c -> cbt.computeIfAbsent(tableNameById.get(c.getTableId()), k -> new ArrayList<>()).add(c));
        cbt.replaceAll((k, v) -> List.copyOf(v));
        this.columnsByTable = Map.copyOf(cbt);

        Map<String, SemMetric> mbc = new LinkedHashMap<>();
        metrics.forEach(m -> mbc.put(m.getMetricCode(), m));
        this.metricByCode = Map.copyOf(mbc);

        Map<String, List<SemSynonym>> sbt = new LinkedHashMap<>();
        synonyms.forEach(s -> sbt.computeIfAbsent(s.getTargetType() + ":" + s.getTargetKey(),
                k -> new ArrayList<>()).add(s));
        sbt.replaceAll((k, v) -> List.copyOf(v));
        this.synonymsByTarget = Map.copyOf(sbt);

        Map<String, List<SemEnum>> ebt = new LinkedHashMap<>();
        enums.forEach(e -> ebt.computeIfAbsent(e.getTableName(), k -> new ArrayList<>()).add(e));
        ebt.replaceAll((k, v) -> List.copyOf(v));
        this.enumsByTable = Map.copyOf(ebt);
    }

    public long version() {
        return version;
    }

    public List<SemTable> tables() {
        return tables;
    }

    public List<SemColumn> columns() {
        return columns;
    }

    public List<SemMetric> metrics() {
        return metrics;
    }

    public List<SemSynonym> synonyms() {
        return synonyms;
    }

    public List<SemEnum> enums() {
        return enums;
    }

    public List<FewShotExample> examples() {
        return examples;
    }

    public List<RowPermission> rowPermissions() {
        return rowPermissions;
    }

    public Optional<SemTable> table(String name) {
        return Optional.ofNullable(tableByName.get(name));
    }

    public List<SemColumn> columnsOf(String tableName) {
        return columnsByTable.getOrDefault(tableName, List.of());
    }

    public Map<String, List<SemColumn>> columnsByTable() {
        return columnsByTable;
    }

    public Optional<SemMetric> metric(String code) {
        return Optional.ofNullable(metricByCode.get(code));
    }

    public Map<String, SemMetric> metricsByCode() {
        return metricByCode;
    }

    public List<SemSynonym> synonymsFor(String targetType, String targetKey) {
        return synonymsByTarget.getOrDefault(targetType + ":" + targetKey, List.of());
    }

    public List<SemEnum> enumsOf(String tableName) {
        return enumsByTable.getOrDefault(tableName, List.of());
    }

    public String tableBusinessName(String tableName) {
        SemTable table = tableByName.get(tableName);
        return table == null ? tableName : table.getBusinessName();
    }

    public List<String> enabledTableNames() {
        return tables.stream().filter(t -> Boolean.TRUE.equals(t.getEnabled()))
                .map(SemTable::getTableName).toList();
    }
}
