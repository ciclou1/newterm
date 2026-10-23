package com.chatbi.semantic;

import com.chatbi.domain.entity.FewShotExample;
import com.chatbi.domain.entity.RowPermission;
import com.chatbi.domain.entity.SemColumn;
import com.chatbi.domain.entity.SemEnum;
import com.chatbi.domain.entity.SemMetric;
import com.chatbi.domain.entity.SemSynonym;
import com.chatbi.domain.entity.SemTable;
import com.chatbi.mapper.FewShotExampleMapper;
import com.chatbi.mapper.RowPermissionMapper;
import com.chatbi.mapper.SemColumnMapper;
import com.chatbi.mapper.SemEnumMapper;
import com.chatbi.mapper.SemMetricMapper;
import com.chatbi.mapper.SemSynonymMapper;
import com.chatbi.mapper.SemTableMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
public class SemanticCatalog {

    private final SemTableMapper tableMapper;
    private final SemColumnMapper columnMapper;
    private final SemMetricMapper metricMapper;
    private final SemSynonymMapper synonymMapper;
    private final SemEnumMapper enumMapper;
    private final FewShotExampleMapper exampleMapper;
    private final RowPermissionMapper permissionMapper;

    private final AtomicLong version = new AtomicLong();
    private volatile SemanticSnapshot snapshot;

    public SemanticCatalog(
            SemTableMapper tableMapper,
            SemColumnMapper columnMapper,
            SemMetricMapper metricMapper,
            SemSynonymMapper synonymMapper,
            SemEnumMapper enumMapper,
            FewShotExampleMapper exampleMapper,
            RowPermissionMapper permissionMapper) {
        this.tableMapper = tableMapper;
        this.columnMapper = columnMapper;
        this.metricMapper = metricMapper;
        this.synonymMapper = synonymMapper;
        this.enumMapper = enumMapper;
        this.exampleMapper = exampleMapper;
        this.permissionMapper = permissionMapper;
    }

    @PostConstruct
    public void initialize() {
        refresh();
    }

    public synchronized SemanticSnapshot refresh() {
        List<SemTable> tables = tableMapper.selectList(null);
        List<SemColumn> columns = columnMapper.selectList(null);
        List<SemMetric> metrics = metricMapper.selectList(null);
        List<SemSynonym> synonyms = synonymMapper.selectList(null);
        List<SemEnum> enums = enumMapper.selectList(null);
        List<FewShotExample> examples = exampleMapper.selectList(null);
        List<RowPermission> permissions = permissionMapper.selectList(null);

        List<SemTable> enabledTables = tables.stream().filter(t -> Boolean.TRUE.equals(t.getEnabled())).toList();
        List<String> enabledNames = enabledTables.stream().map(SemTable::getTableName).toList();
        List<SemColumn> enabledColumns = columns.stream()
                .filter(c -> enabledNames.contains(tableName(tables, c.getTableId())))
                .toList();
        List<SemMetric> enabledMetrics = metrics.stream().filter(m -> Boolean.TRUE.equals(m.getEnabled())).toList();
        List<FewShotExample> enabledExamples = examples.stream().filter(e -> Boolean.TRUE.equals(e.getEnabled())).toList();
        List<SemSynonym> enabledSynonyms = synonyms;

        snapshot = new SemanticSnapshot(version.incrementAndGet(), enabledTables, enabledColumns, enabledMetrics,
                enabledSynonyms, enums, enabledExamples, permissions);
        log.info("Semantic catalog loaded: {} tables, {} columns, {} metrics, {} examples (version {})",
                enabledTables.size(), enabledColumns.size(), enabledMetrics.size(), enabledExamples.size(),
                snapshot.version());
        return snapshot;
    }

    public SemanticSnapshot snapshot() {
        SemanticSnapshot current = snapshot;
        if (current == null) {
            return refresh();
        }
        return current;
    }

    private String tableName(List<SemTable> tables, Long id) {
        return tables.stream().filter(t -> t.getId().equals(id)).map(SemTable::getTableName).findFirst().orElse("");
    }
}
