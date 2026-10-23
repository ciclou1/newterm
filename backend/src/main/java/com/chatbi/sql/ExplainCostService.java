package com.chatbi.sql;

import com.chatbi.config.ChatBiProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class ExplainCostService {

    private final DataSource queryDataSource;
    private final ObjectMapper objectMapper;
    private final ChatBiProperties properties;

    public ExplainCostService(@Qualifier("queryDataSource") DataSource queryDataSource,
                              ObjectMapper objectMapper,
                              ChatBiProperties properties) {
        this.queryDataSource = queryDataSource;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public ExplainResult explain(String sql) {
        long start = System.currentTimeMillis();
        try (Connection connection = queryDataSource.getConnection()) {
            connection.setReadOnly(true);
            try (Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(properties.getQuery().getExplainTimeoutSeconds());
                try (ResultSet rs = statement.executeQuery("EXPLAIN (FORMAT JSON) " + sql)) {
                    if (!rs.next()) {
                        return new ExplainResult(null, 0, List.of(), "EXPLAIN 未返回计划");
                    }
                    String json = rs.getString(1);
                    JsonNode root = objectMapper.readTree(json);
                    JsonNode plan = root.isArray() && !root.isEmpty() ? root.get(0).get("Plan") : null;
                    if (plan == null) {
                        return new ExplainResult(json, 0, List.of(), null);
                    }
                    double cost = plan.path("Total Cost").asDouble(0);
                    List<String> nodes = new ArrayList<>();
                    collectNodes(plan, nodes, 0);
                    long elapsed = System.currentTimeMillis() - start;
                    log.debug("EXPLAIN cost={} elapsed={}ms nodes={}", cost, elapsed, nodes);
                    return new ExplainResult(json, cost, nodes, null);
                }
            }
        } catch (Exception e) {
            return new ExplainResult(null, 0, List.of(), friendly(e));
        }
    }

    private void collectNodes(JsonNode node, List<String> nodes, int depth) {
        if (node == null || depth > 8 || nodes.size() >= 8) {
            return;
        }
        String type = node.path("Node Type").asText("");
        String relation = node.path("Relation Name").asText("");
        if (!type.isBlank()) {
            nodes.add(relation.isBlank() ? type : type + "(" + relation + ")");
        }
        JsonNode children = node.get("Plans");
        if (children != null && children.isArray()) {
            for (JsonNode child : children) {
                collectNodes(child, nodes, depth + 1);
            }
        }
    }

    private String friendly(Exception e) {
        String message = e.getMessage();
        if (message == null) {
            return "EXPLAIN 执行失败";
        }
        int newline = message.indexOf('\n');
        return newline > 0 ? message.substring(0, newline) : message;
    }

    public record ExplainResult(String rawPlan, double totalCost, List<String> nodes, String error) {
        public boolean ok() {
            return error == null;
        }
    }
}
