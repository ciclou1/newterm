package com.chatbi.sql;

import com.chatbi.config.ChatBiProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Connection;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Executes already-validated SQL on the read-only pool. Limits are enforced on
 * the JDBC statement in addition to the database role's SELECT-only grants.
 */
@Slf4j
@Component
public class QueryExecutor {

    private final DataSource queryDataSource;
    private final ChatBiProperties properties;

    public QueryExecutor(@Qualifier("queryDataSource") DataSource queryDataSource,
                         ChatBiProperties properties) {
        this.queryDataSource = queryDataSource;
        this.properties = properties;
    }

    public QueryExecution execute(String sql) {
        long start = System.currentTimeMillis();
        int maxRows = properties.getQuery().getMaxRows();
        try (Connection connection = queryDataSource.getConnection()) {
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(properties.getQuery().getTimeoutSeconds());
                statement.setFetchSize(200);
                statement.setMaxRows(maxRows + 1);
                try (ResultSet rs = statement.executeQuery(sql)) {
                    ResultSetMetaData meta = rs.getMetaData();
                    List<ResultColumn> columns = new ArrayList<>();
                    List<String> labels = new ArrayList<>();
                    for (int i = 1; i <= meta.getColumnCount(); i++) {
                        String label = uniqueLabel(labels, meta.getColumnLabel(i));
                        labels.add(label);
                        columns.add(new ResultColumn(label, meta.getColumnTypeName(i)));
                    }
                    List<Map<String, Object>> rows = new ArrayList<>();
                    boolean truncated = false;
                    while (rs.next()) {
                        if (rows.size() >= maxRows) {
                            truncated = true;
                            break;
                        }
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= meta.getColumnCount(); i++) {
                            row.put(labels.get(i - 1), normalize(rs.getObject(i)));
                        }
                        rows.add(row);
                    }
                    connection.rollback();
                    return new QueryExecution(true, columns, rows, rows.size() > 0, truncated,
                            System.currentTimeMillis() - start, null);
                }
            } finally {
                if (!connection.getAutoCommit()) {
                    connection.setAutoCommit(true);
                }
            }
        } catch (SQLException e) {
            String message = friendly(e);
            log.info("Query execution failed: {}", message);
            return new QueryExecution(false, List.of(), List.of(), false, false,
                    System.currentTimeMillis() - start, message);
        }
    }

    private Object normalize(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Timestamp ts) {
            return ts.toLocalDateTime().toString().replace('T', ' ');
        }
        if (value instanceof Date d) {
            return d.toLocalDate().toString();
        }
        if (value instanceof Time t) {
            return t.toLocalTime().toString();
        }
        if (value instanceof OffsetDateTime odt) {
            return odt.toString();
        }
        if (value instanceof Array array) {
            try {
                Object data = array.getArray();
                if (data instanceof Object[] objects) {
                    return List.of(objects);
                }
                return data;
            } catch (SQLException e) {
                return value.toString();
            }
        }
        return value.toString();
    }

    private String uniqueLabel(List<String> labels, String raw) {
        String base = raw == null || raw.isBlank() ? "column" : raw;
        String candidate = base;
        int suffix = 2;
        while (labels.contains(candidate)) {
            candidate = base + "_" + suffix++;
        }
        return candidate;
    }

    private String friendly(SQLException e) {
        String message = e.getMessage();
        if (message == null) {
            return "查询执行失败";
        }
        int newline = message.indexOf('\n');
        return newline > 0 ? message.substring(0, newline) : message;
    }

    public record ResultColumn(String name, String type) {
    }

    public record QueryExecution(boolean success, List<ResultColumn> columns, List<Map<String, Object>> rows,
                                 boolean hasRows, boolean truncated, long elapsedMs, String error) {
    }
}
