package com.chatbi.semantic;

import com.chatbi.domain.entity.FewShotExample;
import com.chatbi.domain.entity.SemColumn;
import com.chatbi.domain.entity.SemEnum;
import com.chatbi.domain.entity.SemMetric;
import com.chatbi.domain.entity.SemSynonym;
import com.chatbi.domain.entity.SemTable;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
public class SemanticIndexer {

    private static final List<String> OWNER_TYPES = List.of("TABLE", "COLUMN", "METRIC", "FEWSHOT");

    private final SemanticCatalog catalog;
    private final SemanticEmbeddingRepository embeddingRepository;
    private final ObjectMapper objectMapper;

    public SemanticIndexer(SemanticCatalog catalog,
                           SemanticEmbeddingRepository embeddingRepository,
                           ObjectMapper objectMapper) {
        this.catalog = catalog;
        this.embeddingRepository = embeddingRepository;
        this.objectMapper = objectMapper;
    }

    public synchronized IndexStats reindexAll() {        SemanticSnapshot snapshot = catalog.snapshot();
        List<Doc> docs = buildDocuments(snapshot);
        Map<String, Set<String>> keysByType = new LinkedHashMap<>();
        OWNER_TYPES.forEach(t -> keysByType.put(t, new HashSet<>()));

        int inserted = 0;
        int skipped = 0;
        for (String type : OWNER_TYPES) {
            Map<String, String> existing = embeddingRepository.hashes(type);
            List<Doc> typed = docs.stream().filter(d -> d.type.equals(type)).toList();
            for (Doc doc : typed) {
                keysByType.get(type).add(doc.key);
                String hash = sha256(doc.content);
                if (hash.equals(existing.get(doc.key))) {
                    skipped++;
                    continue;
                }
                embeddingRepository.upsert(type, doc.key, doc.content, hash, metadata(doc));
                inserted++;
            }
            for (String existingKey : existing.keySet()) {
                if (!keysByType.get(type).contains(existingKey)) {
                    embeddingRepository.delete(type, existingKey);
                }
            }
        }
        log.info("Semantic index refreshed: {} upserted, {} unchanged, {} documents total",
                inserted, skipped, docs.size());
        return new IndexStats(docs.size(), inserted, skipped, snapshot.version());
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        try {
            IndexStats stats = reindexAll();
            log.info("Semantic embeddings ready at startup: {}/{} documents, version {}",
                    stats.documents(), stats.upserted(), stats.version());
        } catch (Exception e) {
            log.warn("Semantic embedding warm-up skipped: {}", e.getMessage());
        }
    }

    private List<Doc> buildDocuments(SemanticSnapshot snapshot) {
        List<Doc> docs = new ArrayList<>();
        Map<String, List<SemEnum>> enumsByTable = new LinkedHashMap<>();
        snapshot.enums().forEach(e -> enumsByTable.computeIfAbsent(e.getTableName(), k -> new ArrayList<>()).add(e));

        for (SemTable table : snapshot.tables()) {
            List<SemColumn> columns = snapshot.columnsOf(table.getTableName());
            List<SemSynonym> synonyms = snapshot.synonymsFor("TABLE", table.getTableName());

            StringBuilder tableContent = new StringBuilder()
                    .append(table.getBusinessName()).append(' ').append(table.getTableName()).append(' ')
                    .append(table.getDomain()).append(' ').append(table.getDescription()).append(' ');
            synonyms.forEach(s -> tableContent.append(s.getSynonym()).append(' '));
            columns.forEach(c -> tableContent.append(c.getBusinessName()).append(' ').append(c.getColumnName()).append(' '));
            docs.add(new Doc("TABLE", table.getTableName(), tableContent.toString(),
                    Map.of("label", table.getBusinessName(), "domain", table.getDomain())));

            for (SemColumn column : columns) {
                List<SemSynonym> columnSynonyms =
                        snapshot.synonymsFor("COLUMN", table.getTableName() + "." + column.getColumnName());
                StringBuilder content = new StringBuilder()
                        .append(table.getBusinessName()).append(' ')
                        .append(column.getBusinessName()).append(' ').append(column.getColumnName()).append(' ')
                        .append(column.getDescription() == null ? "" : column.getDescription()).append(' ');
                columnSynonyms.forEach(s -> content.append(s.getSynonym()).append(' '));
                docs.add(new Doc("COLUMN", table.getTableName() + "." + column.getColumnName(), content.toString(),
                        Map.of("table", table.getTableName(), "label", column.getBusinessName())));
            }
        }

        for (SemMetric metric : snapshot.metrics()) {
            String content = metric.getMetricName() + " " + metric.getMetricCode() + " " + metric.getDescription()
                    + " " + metric.getSynonymsText() + " " + snapshot.tableBusinessName(metric.getBaseTable());
            docs.add(new Doc("METRIC", metric.getMetricCode(), content,
                    Map.of("label", metric.getMetricName(), "table", metric.getBaseTable())));
        }

        for (FewShotExample example : snapshot.examples()) {
            String content = example.getQuestion() + " " + example.getTags() + " " + example.getTablesUsed();
            docs.add(new Doc("FEWSHOT", String.valueOf(example.getId()), content,
                    Map.of("question", example.getQuestion())));
        }
        return docs;
    }

    private String metadata(Doc doc) {
        try {
            return objectMapper.writeValueAsString(doc.metadata);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private record Doc(String type, String key, String content, Map<String, String> metadata) {
    }

    public record IndexStats(int documents, int upserted, int unchanged, long version) {
    }
}
