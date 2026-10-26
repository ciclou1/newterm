package com.chatbi.web;

import com.chatbi.common.ChatBiException;
import com.chatbi.domain.entity.SemColumn;
import com.chatbi.domain.entity.SemEnum;
import com.chatbi.domain.entity.SemMetric;
import com.chatbi.domain.entity.SemTable;
import com.chatbi.mapper.FewShotExampleMapper;
import com.chatbi.mapper.RowPermissionMapper;
import com.chatbi.mapper.SemColumnMapper;
import com.chatbi.mapper.SemEnumMapper;
import com.chatbi.mapper.SemMetricMapper;
import com.chatbi.mapper.SemSynonymMapper;
import com.chatbi.mapper.SemTableMapper;
import com.chatbi.semantic.SemanticCatalog;
import com.chatbi.semantic.SemanticIndexer;
import com.chatbi.semantic.SemanticSnapshot;
import com.chatbi.service.CurrentUserService;
import com.chatbi.sql.SqlAstValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SemanticControllerTest {

    private final SemanticCatalog catalog = mock(SemanticCatalog.class);
    private final SemanticIndexer indexer = mock(SemanticIndexer.class);
    private final CurrentUserService currentUserService = mock(CurrentUserService.class);
    private final SemMetricMapper metricMapper = mock(SemMetricMapper.class);
    private final SemSynonymMapper synonymMapper = mock(SemSynonymMapper.class);
    private final SemEnumMapper enumMapper = mock(SemEnumMapper.class);
    private final FewShotExampleMapper exampleMapper = mock(FewShotExampleMapper.class);
    private final RowPermissionMapper permissionMapper = mock(RowPermissionMapper.class);
    private final SemTableMapper tableMapper = mock(SemTableMapper.class);
    private final SemColumnMapper columnMapper = mock(SemColumnMapper.class);
    private final SqlAstValidator validator = mock(SqlAstValidator.class);

    private final SemTable table = new SemTable();
    private final SemColumn column = new SemColumn();
    private SemanticController controller;

    @BeforeEach
    void setUp() {
        table.setId(1L);
        table.setTableName("sales_order");
        table.setBusinessName("销售订单");
        table.setDomain("sales");
        table.setEnabled(true);

        column.setId(2L);
        column.setTableId(1L);
        column.setColumnName("region");
        column.setBusinessName("区域");
        column.setIsDimension(true);
        column.setIsMeasure(false);
        column.setSensitive(false);

        SemanticSnapshot snapshot = new SemanticSnapshot(7, List.of(table), List.of(column),
                List.of(), List.of(), List.of(), List.of(), List.of());
        when(catalog.snapshot()).thenReturn(snapshot);
        when(catalog.refresh()).thenReturn(snapshot);

        controller = new SemanticController(catalog, indexer, currentUserService, metricMapper,
                synonymMapper, enumMapper, exampleMapper, permissionMapper, tableMapper,
                columnMapper, validator);
    }

    @Test
    void deletesMetricAndReloadsTheCatalog() {
        when(metricMapper.deleteById(9L)).thenReturn(1);

        controller.deleteMetric(9L);

        verify(metricMapper).deleteById(9L);
        verify(catalog).refresh();
        verify(indexer).reindexAll();
    }

    @Test
    void deletingAMissingMetricIsNotFound() {
        when(metricMapper.deleteById(9L)).thenReturn(0);

        assertThatThrownBy(() -> controller.deleteMetric(9L))
                .isInstanceOfSatisfying(ChatBiException.class,
                        error -> assertThat(error.getCode()).isEqualTo("NOT_FOUND"));
        verify(catalog, never()).refresh();
    }

    @Test
    void nonAdminCannotDeleteSemanticData() {
        doThrow(ChatBiException.forbidden("ADMIN_REQUIRED", "forbidden"))
                .when(currentUserService).requireAdmin();

        assertThatThrownBy(() -> controller.deleteMetric(9L))
                .isInstanceOfSatisfying(ChatBiException.class,
                        error -> assertThat(error.getCode()).isEqualTo("ADMIN_REQUIRED"));
        verifyNoInteractions(metricMapper);
    }

    @Test
    void savesAnEnumAndPersistsNormalisedValues() {
        when(enumMapper.selectOne(any())).thenReturn(null);

        controller.saveEnum(new SemanticController.EnumRequest(null, "sales_order", "REGION",
                "east", "华东", null));

        org.mockito.ArgumentCaptor<SemEnum> captor = org.mockito.ArgumentCaptor.forClass(SemEnum.class);
        verify(enumMapper).insert(captor.capture());
        assertThat(captor.getValue().getColumnName()).isEqualTo("region");
        assertThat(captor.getValue().getEnumLabel()).isEqualTo("华东");
    }

    @Test
    void rejectsAnEnumForAnUnknownColumn() {
        assertThatThrownBy(() -> controller.saveEnum(new SemanticController.EnumRequest(null,
                "sales_order", "ghost", "x", "X", null)))
                .isInstanceOfSatisfying(ChatBiException.class,
                        error -> assertThat(error.getCode()).isEqualTo("INVALID_ENUM_COLUMN"));
        verifyNoInteractions(enumMapper);
    }

    @Test
    void editsTableMetadataButKeepsThePhysicalName() {
        when(tableMapper.selectById(1L)).thenReturn(table);

        SemTable updated = controller.updateTable(1L,
                new SemanticController.TableRequest("销售订单明细", "sales", "新说明", false));

        assertThat(updated.getTableName()).isEqualTo("sales_order");
        assertThat(updated.getBusinessName()).isEqualTo("销售订单明细");
        assertThat(updated.getDescription()).isEqualTo("新说明");
        assertThat(updated.getEnabled()).isFalse();
        verify(tableMapper).updateById(table);
        verify(catalog).refresh();
    }

    @Test
    void editsColumnMetadataAndFlags() {
        when(columnMapper.selectById(2L)).thenReturn(column);

        SemColumn updated = controller.updateColumn(2L,
                new SemanticController.ColumnRequest("销售区域", "说明", "区域", null, true, true));

        assertThat(updated.getBusinessName()).isEqualTo("销售区域");
        assertThat(updated.getUnit()).isEqualTo("区域");
        assertThat(updated.getIsDimension()).isTrue();
        assertThat(updated.getIsMeasure()).isTrue();
        assertThat(updated.getSensitive()).isTrue();
        verify(columnMapper).updateById(column);
    }

    @Test
    void updatingAMissingTableIsNotFound() {
        when(tableMapper.selectById(99L)).thenReturn(null);

        assertThatThrownBy(() -> controller.updateTable(99L,
                new SemanticController.TableRequest("x", "sales", null, true)))
                .isInstanceOfSatisfying(ChatBiException.class,
                        error -> assertThat(error.getCode()).isEqualTo("NOT_FOUND"));
    }
}
