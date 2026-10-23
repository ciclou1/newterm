package com.chatbi.linking;

import com.chatbi.config.ChatBiProperties;
import com.chatbi.domain.entity.FewShotExample;
import com.chatbi.domain.entity.SemColumn;
import com.chatbi.domain.entity.SemEnum;
import com.chatbi.domain.entity.SemMetric;
import com.chatbi.domain.entity.SemSynonym;
import com.chatbi.domain.entity.SemTable;
import com.chatbi.linking.SchemaContext.ColumnDoc;
import com.chatbi.linking.SchemaContext.EnumDoc;
import com.chatbi.linking.SchemaContext.ExampleDoc;
import com.chatbi.linking.SchemaContext.MetricDoc;
import com.chatbi.linking.SchemaContext.SchemaHit;
import com.chatbi.linking.SchemaContext.TableDoc;
import com.chatbi.semantic.SchemaLinkCache;
import com.chatbi.semantic.SemanticCatalog;
import com.chatbi.semantic.SemanticEmbeddingRepository;
import com.chatbi.semantic.SemanticSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Hybrid schema linking: synonym expansion + BM25 + pgvector recall, then a
 * precision pass that prunes tables, columns, metrics and few-shot examples
 * before anything is sent to the LLM.
 */
@Slf4j
@Component
public class SchemaLinker {

    private final SemanticCatalog catalog;
    private final Bm25Index bm25;
    private final TextTokenizer tokenizer;
    private final SemanticEmbeddingRepository embeddings;
    private final SchemaLinkCache cache;
    private final ChatBiProperties properties;

    public SchemaLinker(SemanticCatalog catalog,
                        Bm25Index bm25,
                        TextTokenizer tokenizer,
                        SemanticEmbeddingRepository embeddings,
                        SchemaLinkCache cache,
                        ChatBiProperties properties) {
        this.catalog = catalog;
        this.bm25 = bm25;
        this.tokenizer = tokenizer;
        this.embeddings = embeddings;
        this.cache = cache;
        this.properties = properties;
    }

    public SchemaContext link(String question) {
        SemanticSnapshot snapshot = catalog.snapshot();
        Optional<SchemaContext> cached = cache.get(question, snapshot.version());
        if (cached.isPresent()) {
            return cached.get();
        }

        Expansion expansion = expandSynonyms(question, snapshot);
        String expandedQuery = (question + " " + String.join(" ", expansion.canonicalTerms)).trim();

        List<Bm25Index.Document<SemTable>> tableSearchDocs = snapshot.tables().stream()
                .map(t -> new Bm25Index.Document<>(t.getTableName(), t, tableText(t, snapshot)))
                .toList();
        Map<String, Double> tableBm25 = normalize(bm25.search(expandedQuery, tableSearchDocs, 20));
        Map<String, Double> tableVector = vectorScores("TABLE", expandedQuery, 20);

        List<Bm25Index.Document<SemMetric>> metricSearchDocs = snapshot.metrics().stream()
                .map(m -> new Bm25Index.Document<>(m.getMetricCode(), m, metricText(m)))
                .toList();
        Map<String, Double> metricBm25 = normalize(bm25.search(expandedQuery, metricSearchDocs, 20));
        Map<String, Double> metricVector = vectorScores("METRIC", expandedQuery, 20);

        List<Bm25Index.Document<SemColumn>> columnDocs = snapshot.columns().stream()
                .map(c -> new Bm25Index.Document<>(columnKey(c, snapshot), c, columnText(c, snapshot)))
                .toList();
        Map<String, Double> columnBm25 = normalize(bm25.search(expandedQuery, columnDocs, 60));
        Map<String, Double> columnVector = vectorScores("COLUMN", expandedQuery, 60);

        // ---- metric ranking ----
        Map<String, Double> metricScores = new LinkedHashMap<>();
        Map<String, String> metricReasons = new HashMap<>();
        for (SemMetric metric : snapshot.metrics()) {
            double score = 0.55 * metricBm25.getOrDefault(metric.getMetricCode(), 0.0)
                    + 0.30 * metricVector.getOrDefault(metric.getMetricCode(), 0.0);
            String alias = longestAliasInQuestion(question, aliases(metric));
            if (alias != null) {
                score += 1.2 + Math.min(alias.length(), 6) * 0.05;
                metricReasons.put(metric.getMetricCode(), "同义词命中「" + alias + "」");
            }
            if (question.contains(metric.getMetricName())) {
                score += 0.8;
                metricReasons.putIfAbsent(metric.getMetricCode(), "指标名称命中");
            }
            if (expansion.metricCodes().contains(metric.getMetricCode())) {
                score += 0.5;
            }
            if (score > 0.25) {
                metricScores.put(metric.getMetricCode(), score);
            }
        }
        List<SemMetric> selectedMetrics = snapshot.metrics().stream()
                .filter(m -> metricScores.containsKey(m.getMetricCode()))
                .sorted(Comparator.comparingDouble((SemMetric m) -> metricScores.get(m.getMetricCode())).reversed())
                .limit(properties.getSchemaLink().getMaxMetrics())
                .toList();

        // ---- table ranking ----
        Map<String, Double> tableScores = new LinkedHashMap<>();
        Map<String, String> tableReasons = new HashMap<>();
        Set<String> metricTables = new HashSet<>();
        selectedMetrics.forEach(m -> metricTables.add(m.getBaseTable()));

        for (SemTable table : snapshot.tables()) {
            double score = 0.50 * tableBm25.getOrDefault(table.getTableName(), 0.0)
                    + 0.35 * tableVector.getOrDefault(table.getTableName(), 0.0);
            if (question.contains(table.getBusinessName()) || question.contains(table.getTableName())) {
                score += 0.9;
                tableReasons.put(table.getTableName(), "表业务名命中");
            }
            if (metricTables.contains(table.getTableName())) {
                score += 3.0;
                tableReasons.put(table.getTableName(), "指标所属事实表");
            }
            if (expansion.tableSynonyms().contains(table.getTableName())) {
                score += 0.5;
            }
            tableScores.put(table.getTableName(), score);
        }

        List<SemTable> selectedTables = new ArrayList<>();
        snapshot.tables().stream()
                .filter(t -> metricTables.contains(t.getTableName()))
                .forEach(selectedTables::add);
        snapshot.tables().stream()
                .sorted(Comparator.comparingDouble((SemTable t) -> tableScores.getOrDefault(t.getTableName(), 0.0)).reversed())
                .filter(t -> tableScores.getOrDefault(t.getTableName(), 0.0) > 0.32)
                .forEach(t -> {
                    if (selectedTables.size() < properties.getSchemaLink().getMaxTables()
                            && selectedTables.stream().noneMatch(x -> x.getTableName().equals(t.getTableName()))) {
                        selectedTables.add(t);
                    }
                });
        if (selectedTables.isEmpty() && !snapshot.tables().isEmpty()) {
            selectedTables.add(snapshot.tables().getFirst());
        }
        Set<String> selectedTableNames = new LinkedHashSet<>();
        selectedTables.forEach(t -> selectedTableNames.add(t.getTableName()));

        // ---- columns ----
        ColumnSelection columnSelection = selectColumns(question, snapshot, selectedTableNames,
                columnBm25, columnVector, selectedMetrics);

        List<TableDoc> tableDocs = selectedTables.stream()
                .map(t -> new TableDoc(t.getTableName(), t.getBusinessName(), t.getDomain(), t.getDescription(),
                        columnSelection.byTable().getOrDefault(t.getTableName(), List.of())))
                .toList();

        List<MetricDoc> metricDocs = selectedMetrics.stream()
                .map(m -> new MetricDoc(m.getMetricCode(), m.getMetricName(), m.getDescription(), m.getExpression(),
                        m.getBaseTable(), m.getTimeColumn(), m.getUnit(), m.getValueFormat(), aliases(m)))
                .toList();

        List<EnumDoc> enums = selectEnums(snapshot, selectedTableNames);
        List<ExampleDoc> examples = selectExamples(snapshot, selectedTableNames, expandedQuery);

        List<SchemaHit> tableHits = tableDocs.stream()
                .map(t -> new SchemaHit("TABLE", t.tableName(), t.businessName(),
                        rounded(tableScores.getOrDefault(t.tableName(), 0.0)),
                        tableReasons.getOrDefault(t.tableName(), "融合召回")))
                .sorted(Comparator.comparingDouble(SchemaHit::score).reversed())
                .toList();

        List<SchemaHit> metricHits = metricDocs.stream()
                .map(m -> new SchemaHit("METRIC", m.code(), m.name(),
                        rounded(metricScores.getOrDefault(m.code(), 0.0)),
                        metricReasons.getOrDefault(m.code(), "融合召回")))
                .sorted(Comparator.comparingDouble(SchemaHit::score).reversed())
                .toList();

        List<SchemaHit> columnHits = columnSelection.scored().stream()
                .filter(c -> c.score() > 0.15)
                .limit(8)
                .map(c -> new SchemaHit("COLUMN", c.key(), c.column().getBusinessName(),
                        rounded(c.score()), "字段召回"))
                .toList();

        SchemaContext context = new SchemaContext(tableDocs, metricDocs, examples, enums,
                tableHits, metricHits, columnHits, expansion.displayHits(), expandedQuery, snapshot.version());
        cache.put(question, snapshot.version(), context);
        return context;
    }

    private ColumnSelection selectColumns(
            String question,
            SemanticSnapshot snapshot,
            Set<String> selectedTables,
            Map<String, Double> columnBm25,
            Map<String, Double> columnVector,
            List<SemMetric> selectedMetrics) {
        Set<String> requiredTimeColumns = new HashSet<>();
        selectedMetrics.stream().map(SemMetric::getTimeColumn).filter(Objects::nonNull)
                .forEach(requiredTimeColumns::add);
        List<ScoredColumn> scored = new ArrayList<>();

        for (SemTable table : snapshot.tables()) {
            if (!selectedTables.contains(table.getTableName())) {
                continue;
            }
            for (SemColumn column : snapshot.columnsOf(table.getTableName())) {
                String key = table.getTableName() + "." + column.getColumnName();
                double score = 0.50 * columnBm25.getOrDefault(key, 0.0)
                        + 0.30 * columnVector.getOrDefault(key, 0.0);
                if (column.getBusinessName().length() >= 2 && question.contains(column.getBusinessName())) {
                    score += 1.2;
                }
                if (snapshot.synonymsFor("COLUMN", key).stream().anyMatch(s -> question.contains(s.getSynonym()))) {
                    score += 1.0;
                }
                if (Boolean.TRUE.equals(column.getIsDimension())) {
                    score += 0.08;
                }
                if (requiredTimeColumns.contains(column.getColumnName())) {
                    score += 0.6;
                }
                scored.add(new ScoredColumn(key, table.getTableName(), column, score));
            }
        }
        scored.sort(Comparator.comparingDouble(ScoredColumn::score).reversed());

        Map<String, List<ColumnDoc>> byTable = new LinkedHashMap<>();
        int maxColumns = properties.getSchemaLink().getMaxColumnsPerTable();
        for (ScoredColumn selected : scored) {
            List<ColumnDoc> list = byTable.computeIfAbsent(selected.tableName(), k -> new ArrayList<>());
            if (list.size() < maxColumns) {
                list.add(new ColumnDoc(selected.column().getColumnName(),
                        selected.column().getBusinessName(), selected.column().getDataType(),
                        selected.column().getDescription(), selected.column().getUnit(),
                        Boolean.TRUE.equals(selected.column().getIsDimension()),
                        Boolean.TRUE.equals(selected.column().getIsMeasure())));
            }
        }
        selectedTables.forEach(t -> byTable.computeIfAbsent(t, k -> new ArrayList<>()));
        return new ColumnSelection(byTable, scored.subList(0, Math.min(scored.size(), 24)));
    }

    private List<EnumDoc> selectEnums(SemanticSnapshot snapshot, Set<String> tables) {
        List<EnumDoc> result = new ArrayList<>();
        for (String table : tables) {
            for (SemEnum e : snapshot.enumsOf(table)) {
                result.add(new EnumDoc(e.getTableName(), e.getColumnName(), e.getEnumValue(), e.getEnumLabel()));
                if (result.size() >= 40) {
                    return result;
                }
            }
        }
        return result;
    }

    private List<ExampleDoc> selectExamples(SemanticSnapshot snapshot, Set<String> tables, String expandedQuery) {
        List<Bm25Index.Document<FewShotExample>> docs = snapshot.examples().stream()
                .map(e -> new Bm25Index.Document<>(String.valueOf(e.getId()), e,
                        e.getQuestion() + " " + e.getTags() + " " + e.getTablesUsed()))
                .toList();
        Map<String, Double> bm25Scores = normalize(bm25.search(expandedQuery, docs, 20));
        Map<String, Double> vectorScores = vectorScores("FEWSHOT", expandedQuery, 20);
        List<ExampleDoc> filtered = snapshot.examples().stream()
                .map(e -> new ScoredExample(e,
                        0.6 * bm25Scores.getOrDefault(String.valueOf(e.getId()), 0.0)
                                + 0.4 * vectorScores.getOrDefault(String.valueOf(e.getId()), 0.0)))
                .filter(s -> s.score() > 0.08)
                .sorted(Comparator.comparingDouble(ScoredExample::score).reversed())
                .filter(s -> intersects(s.example().getTablesUsed(), tables))
                .limit(properties.getSchemaLink().getMaxExamples())
                .map(s -> new ExampleDoc(s.example().getId(), s.example().getQuestion(),
                        s.example().getSqlText(), s.example().getTablesUsed(), s.example().getTags()))
                .toList();
        if (!filtered.isEmpty()) {
            return filtered;
        }
        return snapshot.examples().stream().limit(2)
                .map(e -> new ExampleDoc(e.getId(), e.getQuestion(), e.getSqlText(), e.getTablesUsed(), e.getTags()))
                .toList();
    }

    private boolean intersects(String tablesUsed, Set<String> selected) {
        if (tablesUsed == null || tablesUsed.isBlank()) {
            return true;
        }
        for (String table : tablesUsed.split("[,，\\s]+")) {
            if (selected.contains(table)) {
                return true;
            }
        }
        return false;
    }

    private Expansion expandSynonyms(String question, SemanticSnapshot snapshot) {
        Set<String> canonical = new LinkedHashSet<>();
        List<String> displayHits = new ArrayList<>();
        Set<String> tableSynonyms = new LinkedHashSet<>();
        Set<String> metricCodes = new HashSet<>();

        for (SemSynonym synonym : snapshot.synonyms()) {
            if (synonym.getSynonym().length() >= 2 && question.contains(synonym.getSynonym())) {
                String label;
                if ("TABLE".equals(synonym.getTargetType())) {
                    label = snapshot.tableBusinessName(synonym.getTargetKey());
                    tableSynonyms.add(synonym.getTargetKey());
                } else {
                    String[] parts = synonym.getTargetKey().split("\\.");
                    label = parts.length > 1 ? parts[1] : synonym.getTargetKey();
                    if (parts.length > 1) {
                        snapshot.table(parts[0]).ifPresent(t -> {
                            canonical.add(t.getBusinessName());
                            t.getBusinessName();
                        });
                        label = snapshot.table(parts[0]).flatMap(t -> snapshot.columnsOf(t.getTableName()).stream()
                                        .filter(c -> c.getColumnName().equals(parts[1])).findFirst())
                                .map(SemColumn::getBusinessName).orElse(label);
                    }
                }
                canonical.add(label);
                displayHits.add(synonym.getSynonym() + " → " + label);
            }
        }

        for (SemMetric metric : snapshot.metrics()) {
            String matched = longestAliasInQuestion(question, aliases(metric));
            if (matched != null) {
                metricCodes.add(metric.getMetricCode());
                canonical.add(metric.getMetricName());
                displayHits.add(matched + " → " + metric.getMetricName());
            }
        }

        for (SemEnum e : snapshot.enums()) {
            if (e.getEnumLabel().length() >= 2 && question.contains(e.getEnumLabel())) {
                canonical.add(e.getEnumLabel());
                displayHits.add(e.getEnumLabel() + " = " + e.getEnumValue());
            }
        }

        return new Expansion(Set.copyOf(canonical), List.copyOf(new LinkedHashSet<>(displayHits)),
                Set.copyOf(tableSynonyms), Set.copyOf(metricCodes));
    }

    private String tableText(SemTable table, SemanticSnapshot snapshot) {
        StringBuilder sb = new StringBuilder(table.getBusinessName()).append(' ')
                .append(table.getTableName()).append(' ').append(table.getDomain()).append(' ')
                .append(table.getDescription());
        snapshot.columnsOf(table.getTableName()).forEach(c -> sb.append(' ').append(c.getBusinessName()));
        snapshot.synonymsFor("TABLE", table.getTableName()).forEach(s -> sb.append(' ').append(s.getSynonym()));
        return sb.toString();
    }

    private String metricText(SemMetric metric) {
        return metric.getMetricName() + " " + metric.getMetricCode() + " " + metric.getDescription()
                + " " + metric.getSynonymsText();
    }

    private String columnText(SemColumn column, SemanticSnapshot snapshot) {
        String table = snapshot.tables().stream()
                .filter(t -> t.getId().equals(column.getTableId())).map(SemTable::getBusinessName)
                .findFirst().orElse("");
        StringBuilder sb = new StringBuilder(table).append(' ')
                .append(column.getBusinessName()).append(' ').append(column.getColumnName()).append(' ')
                .append(column.getDescription() == null ? "" : column.getDescription());
        return sb.toString();
    }

    private String columnKey(SemColumn column, SemanticSnapshot snapshot) {
        String table = snapshot.tables().stream()
                .filter(t -> t.getId().equals(column.getTableId())).map(SemTable::getTableName)
                .findFirst().orElse("");
        return table + "." + column.getColumnName();
    }

    private List<String> aliases(SemMetric metric) {
        Set<String> aliases = new LinkedHashSet<>();
        aliases.add(metric.getMetricName());
        if (metric.getSynonymsText() != null) {
            for (String s : metric.getSynonymsText().split("[,，、]")) {
                if (!s.isBlank()) {
                    aliases.add(s.trim());
                }
            }
        }
        return new ArrayList<>(aliases);
    }

    private String longestAliasInQuestion(String question, List<String> aliases) {
        String normalized = tokenizer.normalize(question);
        return aliases.stream()
                .filter(a -> a.length() >= 2 && normalized.contains(tokenizer.normalize(a)))
                .max(Comparator.comparingInt(String::length))
                .orElse(null);
    }

    private Map<String, Double> vectorScores(String type, String query, int limit) {
        try {
            Map<String, Double> scores = new HashMap<>();
            for (SemanticEmbeddingRepository.VectorHit hit : embeddings.search(type, query, limit)) {
                scores.put(hit.key(), Math.max(0, hit.score()));
            }
            return scores;
        } catch (Exception e) {
            log.debug("Vector search unavailable for {}: {}", type, e.getMessage());
            return Map.of();
        }
    }

    private Map<String, Double> normalize(List<? extends Bm25Index.Scored<?>> hits) {
        Map<String, Double> result = new HashMap<>();
        if (hits.isEmpty()) {
            return result;
        }
        double max = hits.getFirst().score();
        for (Bm25Index.Scored<?> hit : hits) {
            result.put(hit.id(), max <= 0 ? 0 : hit.score() / max);
        }
        return result;
    }

    private double rounded(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    private record Expansion(Set<String> canonicalTerms, List<String> displayHits,
                             Set<String> tableSynonyms, Set<String> metricCodes) {
    }

    private record ScoredColumn(String key, String tableName, SemColumn column, double score) {
    }

    private record ColumnSelection(Map<String, List<ColumnDoc>> byTable, List<ScoredColumn> scored) {
    }

    private record ScoredExample(FewShotExample example, double score) {
    }
}
