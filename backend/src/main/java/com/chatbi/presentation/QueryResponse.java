package com.chatbi.presentation;

import com.chatbi.nl2sql.AgentResult;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * API-facing answer. The raw AgentResult is included so the UI can expose the
 * full safety/observability trace without a second query.
 */
public record QueryResponse(
        String requestId,
        UUID conversationId,
        Long auditId,
        String answer,
        ChartSpec chart,
        TableView table,
        List<String> followUps,
        AgentResult result) {

    public record ChartSpec(String type, String title, String xAxisName, String yAxisName,
                            List<String> categories, List<Series> series, boolean horizontal) {
    }

    public record Series(String name, List<Object> data) {
    }

    public record TableView(List<TableColumn> columns, List<Map<String, Object>> rows, boolean truncated) {
    }

    public record TableColumn(String key, String label, String type, String unit, String format) {
    }
}
