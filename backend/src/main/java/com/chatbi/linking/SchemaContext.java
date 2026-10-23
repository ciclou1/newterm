package com.chatbi.linking;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public record SchemaContext(
        List<TableDoc> tables,
        List<MetricDoc> metrics,
        List<ExampleDoc> examples,
        List<EnumDoc> enums,
        List<SchemaHit> tableHits,
        List<SchemaHit> metricHits,
        List<SchemaHit> columnHits,
        List<String> synonymHits,
        String expandedQuery,
        long version) {

    public List<String> tableNames() {
        return tables.stream().map(TableDoc::tableName).toList();
    }

    public Optional<MetricDoc> metric(String code) {
        return metrics.stream().filter(m -> m.code().equals(code)).findFirst();
    }

    public Optional<TableDoc> table(String tableName) {
        return tables.stream().filter(t -> t.tableName().equals(tableName)).findFirst();
    }

    public Map<String, MetricDoc> metricsByCode() {
        Map<String, MetricDoc> result = new LinkedHashMap<>();
        metrics.forEach(m -> result.put(m.code(), m));
        return result;
    }

    public record TableDoc(
            String tableName,
            String businessName,
            String domain,
            String description,
            List<ColumnDoc> columns) {
    }

    public record ColumnDoc(
            String name,
            String businessName,
            String dataType,
            String description,
            String unit,
            boolean dimension,
            boolean measure) {
    }

    public record MetricDoc(
            String code,
            String name,
            String description,
            String expression,
            String baseTable,
            String timeColumn,
            String unit,
            String valueFormat,
            List<String> synonyms) {
    }

    public record ExampleDoc(long id, String question, String sql, String tables, String tags) {
    }

    public record EnumDoc(String table, String column, String value, String label) {
    }

    public record SchemaHit(String type, String key, String label, double score, String reason) {
    }
}
