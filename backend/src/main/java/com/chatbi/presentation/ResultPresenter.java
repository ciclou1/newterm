package com.chatbi.presentation;

import com.chatbi.domain.entity.SemColumn;
import com.chatbi.domain.entity.SemMetric;
import com.chatbi.domain.entity.SemTable;
import com.chatbi.nl2sql.AgentResult;
import com.chatbi.semantic.SemanticCatalog;
import com.chatbi.semantic.SemanticSnapshot;
import com.chatbi.sql.QueryExecutor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Turns an execution result into the three presentation layers the product
 * promises: a conclusion, a chart suggestion and a labelled table.
 */
@Component
public class ResultPresenter {

    private static final Pattern TIME_VALUE = Pattern.compile("^\\d{4}[-/]\\d{1,2}([-/]\\d{1,2})?.*$");
    private static final Set<String> TIME_DIMENSIONS = Set.of(
            "order_month", "planned_month", "invoice_month", "order_date", "planned_date",
            "invoice_date", "purchase_date", "due_date", "completed_date", "updated_at");

    private final SemanticCatalog catalog;

    public ResultPresenter(SemanticCatalog catalog) {
        this.catalog = catalog;
    }

    public QueryResponse present(AgentResult result, String requestId, UUID conversationId, Long auditId) {
        SemanticSnapshot snapshot = catalog.snapshot();
        Map<String, MetricMeta> metrics = metricMeta(snapshot);
        Map<String, ColumnMeta> columns = columnMeta(snapshot);

        QueryResponse.TableView table = buildTable(result, metrics, columns);
        QueryResponse.ChartSpec chart = buildChart(result, metrics, columns);
        String answer = buildAnswer(result, metrics, columns);
        List<String> followUps = buildFollowUps(result, metrics);
        return new QueryResponse(requestId, conversationId, auditId, answer, chart, table, followUps, result);
    }

    private QueryResponse.TableView buildTable(AgentResult result, Map<String, MetricMeta> metrics,
                                               Map<String, ColumnMeta> columns) {
        List<QueryResponse.TableColumn> tableColumns = result.result().columns().stream()
                .map(column -> {
                    String key = column.name();
                    MetricMeta metric = metrics.get(key);
                    ColumnMeta semantic = columns.get(key);
                    String label = metric != null ? metric.label()
                            : semantic != null ? semantic.label() : key;
                    String unit = metric != null ? metric.unit()
                            : semantic != null ? semantic.unit() : null;
                    String format = metric != null ? metric.format() : "text";
                    return new QueryResponse.TableColumn(key, label, column.type(), unit, format);
                })
                .toList();
        return new QueryResponse.TableView(tableColumns, result.result().rows(),
                result.result().truncated());
    }

    private QueryResponse.ChartSpec buildChart(AgentResult result, Map<String, MetricMeta> metrics,
                                               Map<String, ColumnMeta> columns) {
        if (!result.ok() || result.result().rows().isEmpty()) {
            return null;
        }
        List<String> numeric = numericColumns(result.result());
        List<String> dimensions = result.result().columns().stream()
                .map(QueryExecutor.ResultColumn::name)
                .filter(name -> !numeric.contains(name))
                .toList();
        if (numeric.isEmpty()) {
            return null;
        }

        List<String> metricLabels = numeric.stream()
                .map(name -> metrics.containsKey(name) ? metrics.get(name).label() : label(columns, name))
                .toList();
        if (dimensions.isEmpty()) {
            List<Object> values = numeric.stream()
                    .map(name -> result.result().rows().getFirst().get(name))
                    .toList();
            return new QueryResponse.ChartSpec("kpi", String.join("、", metricLabels),
                    "指标", "数值", metricLabels, List.of(new QueryResponse.Series("当前值", values)), false);
        }

        String dimension = dimensions.getFirst();
        List<String> categories = result.result().rows().stream()
                .map(row -> dimensions.stream().map(d -> display(row.get(d)))
                        .reduce((a, b) -> a + " / " + b).orElse(""))
                .toList();
        List<QueryResponse.Series> series = numeric.stream()
                .map(name -> new QueryResponse.Series(
                        metrics.containsKey(name) ? metrics.get(name).label() : label(columns, name),
                        result.result().rows().stream().map(row -> row.get(name)).toList()))
                .toList();

        boolean time = isTimeDimension(dimension)
                || result.result().rows().stream().limit(3)
                .map(row -> display(row.get(dimension))).allMatch(v -> TIME_VALUE.matcher(v).matches());
        String question = result.question() == null ? "" : result.question();
        boolean composition = numeric.size() == 1 && categories.size() <= 12
                && (question.contains("占比") || question.contains("构成")
                || question.contains("分布") || question.contains("比例"));
        String type = time ? "line" : composition ? "pie" : "bar";
        int maxLabel = categories.stream().mapToInt(String::length).max().orElse(0);
        boolean horizontal = "bar".equals(type) && (categories.size() > 8 || maxLabel > 8);
        String title = String.join("、", metricLabels) + " · 按" + label(columns, dimension);
        return new QueryResponse.ChartSpec(type, title, label(columns, dimension),
                numeric.size() == 1 ? metricLabels.getFirst() : "数值", categories, series, horizontal);
    }

    private String buildAnswer(AgentResult result, Map<String, MetricMeta> metrics,
                               Map<String, ColumnMeta> columns) {
        return switch (result.status()) {
            case "CLARIFY" -> "需要确认一下口径：" + (result.clarification() == null
                    ? "请补充指标或时间范围。" : result.clarification().question());
            case "BLOCKED" -> "查询被安全策略拦截：" + (result.failure() == null
                    ? "未通过校验" : result.failure().message());
            case "ERROR" -> "本次查询未能完成：" + (result.failure() == null
                    ? "未知错误" : result.failure().message());
            default -> okAnswer(result, metrics, columns);
        };
    }

    private String okAnswer(AgentResult result, Map<String, MetricMeta> metrics,
                            Map<String, ColumnMeta> columns) {
        if (result.result().rows().isEmpty()) {
            return "在当前筛选条件下没有匹配的数据。可以放宽时间范围或去掉部分筛选条件后再试。";
        }
        List<String> numeric = numericColumns(result.result());
        List<String> dimensions = result.result().columns().stream()
                .map(QueryExecutor.ResultColumn::name)
                .filter(name -> !numeric.contains(name)).toList();
        if (numeric.isEmpty()) {
            return "已返回 " + result.result().rowCount() + " 行明细数据，见下方表格。";
        }

        if (dimensions.isEmpty()) {
            Map<String, Object> row = result.result().rows().getFirst();
            List<String> parts = new ArrayList<>();
            for (String name : numeric) {
                parts.add(metricLabel(metrics, columns, name) + "为 " + formatted(row.get(name), meta(metrics, name)));
            }
            return "在当前口径下，" + String.join("，", parts)
                    + "。" + truncatedSuffix(result);
        }

        String dimension = dimensions.getFirst();
        String dimLabel = label(columns, dimension);
        Map<String, Object> best = result.result().rows().getFirst();
        Object bestValue = best.get(numeric.getFirst());
        for (Map<String, Object> row : result.result().rows()) {
            if (compare(row.get(numeric.getFirst()), bestValue) > 0) {
                best = row;
                bestValue = row.get(numeric.getFirst());
            }
        }
        int categoryCount = (int) result.result().rows().stream()
                .map(row -> dimensions.stream().map(d -> display(row.get(d))).toList())
                .distinct().count();
        String metricLabel = metricLabel(metrics, columns, numeric.getFirst());
        Map<String, Object> bestRow = best;
        String bestCategory = dimensions.stream().map(d -> display(bestRow.get(d)))
                .reduce((a, b) -> a + " / " + b).orElse("—");
        StringBuilder answer = new StringBuilder();
        if (isTimeDimension(dimension) && result.result().rows().size() >= 2) {
            Map<String, Object> first = result.result().rows().getFirst();
            Map<String, Object> last = result.result().rows().getLast();
            answer.append("从 ").append(display(first.get(dimension))).append(" 到 ")
                    .append(display(last.get(dimension))).append("，")
                    .append(metricLabel).append("由 ")
                    .append(formatted(first.get(numeric.getFirst()), meta(metrics, numeric.getFirst())))
                    .append(" 变为 ")
                    .append(formatted(last.get(numeric.getFirst()), meta(metrics, numeric.getFirst())));
            double change = changeRate(first.get(numeric.getFirst()), last.get(numeric.getFirst()));
            if (!Double.isNaN(change)) {
                answer.append(change >= 0 ? "，累计增长 " : "，累计下降 ")
                        .append(String.format(Locale.ROOT, "%.1f%%", Math.abs(change)));
            }
            answer.append("。");
        } else {
            answer.append("按").append(dimLabel).append("看共 ").append(categoryCount).append(" 组，")
                    .append(bestCategory).append("的").append(metricLabel).append("最高，为 ")
                    .append(formatted(bestValue, meta(metrics, numeric.getFirst()))).append("。");
            Map<String, Object> worst = result.result().rows().getFirst();
            for (Map<String, Object> row : result.result().rows()) {
                if (compare(row.get(numeric.getFirst()), worst.get(numeric.getFirst())) < 0) {
                    worst = row;
                }
            }
            Map<String, Object> worstRow = worst;
            String worstCategory = dimensions.stream().map(d -> display(worstRow.get(d)))
                    .reduce((a, b) -> a + " / " + b).orElse("—");
            if (!worstCategory.equals(bestCategory)) {
                answer.append("最低为").append(worstCategory).append("（")
                        .append(formatted(worst.get(numeric.getFirst()), meta(metrics, numeric.getFirst())))
                        .append("）。");
            }
        }
        answer.append(truncatedSuffix(result));
        return answer.toString();
    }

    private List<String> buildFollowUps(AgentResult result, Map<String, MetricMeta> metrics) {
        if ("CLARIFY".equals(result.status()) && result.clarification() != null) {
            return result.clarification().options().stream().map(o -> o.question()).limit(4).toList();
        }
        List<String> metricNames = metrics.values().stream()
                .map(MetricMeta::label)
                .filter(label -> result.finalSql() != null && result.finalSql().contains(label))
                .limit(2).toList();
        List<String> columnKeys = result.result().columns().stream()
                .map(QueryExecutor.ResultColumn::name).toList();
        List<String> metricKeys = columnKeys.stream().filter(metrics::containsKey).toList();
        String metric = !metricKeys.isEmpty() ? metrics.get(metricKeys.getFirst()).label()
                : !metricNames.isEmpty() ? metricNames.getFirst() : "销售额";
        Set<String> suggestions = new LinkedHashSet<>();
        if (!result.ok()) {
            suggestions.add("今年销售额是多少");
            suggestions.add("按区域统计销售额");
            suggestions.add("销售额最高的前10个客户");
            return List.copyOf(suggestions);
        }
        suggestions.add("按区域拆分" + metric);
        suggestions.add("近6个月" + metric + "趋势");
        suggestions.add(metric + "最高的前10个客户");
        suggestions.add("按产品类别统计" + metric);
        return suggestions.stream().limit(4).toList();
    }

    private String truncatedSuffix(AgentResult result) {
        return result.result().truncated() ? " 结果超过行数上限，已截断展示。" : "";
    }

    private List<String> numericColumns(AgentResult.QueryResult result) {
        List<String> numeric = new ArrayList<>();
        for (QueryExecutor.ResultColumn column : result.columns()) {
            boolean hasNumber = result.rows().stream()
                    .map(row -> row.get(column.name()))
                    .anyMatch(value -> value instanceof Number);
            if (hasNumber || isNumericType(column.type())) {
                numeric.add(column.name());
            }
        }
        return numeric;
    }

    private boolean isNumericType(String type) {
        if (type == null) {
            return false;
        }
        String lower = type.toLowerCase(Locale.ROOT);
        return lower.contains("int") || lower.contains("numeric") || lower.contains("decimal")
                || lower.contains("double") || lower.contains("real") || lower.contains("float")
                || lower.contains("money");
    }

    private boolean isTimeDimension(String column) {
        return TIME_DIMENSIONS.contains(column) || column.endsWith("_month") || column.endsWith("_date");
    }

    private double changeRate(Object first, Object last) {
        double a = number(first);
        double b = number(last);
        if (Double.isNaN(a) || Double.isNaN(b) || a == 0) {
            return Double.NaN;
        }
        return (b - a) / Math.abs(a) * 100;
    }

    private int compare(Object left, Object right) {
        double a = number(left);
        double b = number(right);
        if (Double.isNaN(a)) {
            return Double.isNaN(b) ? 0 : -1;
        }
        if (Double.isNaN(b)) {
            return 1;
        }
        return Double.compare(a, b);
    }

    private double number(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof String s) {
            try {
                return Double.parseDouble(s.replace(",", ""));
            } catch (NumberFormatException ignored) {
                return Double.NaN;
            }
        }
        return Double.NaN;
    }

    private String formatted(Object value, MetricMeta meta) {
        if (value == null) {
            return "—";
        }
        if (value instanceof Number n) {
            BigDecimal number = new BigDecimal(n.toString());
            if ("percent".equals(meta.format())) {
                return decimal(number, 2) + "%";
            }
            if ("currency".equals(meta.format())) {
                return "¥" + decimal(number, 2);
            }
            String formatted = number.stripTrailingZeros().scale() <= 0
                    ? new DecimalFormat("#,##0").format(number)
                    : decimal(number, 2);
            return meta.unit() == null || meta.unit().isBlank() ? formatted : formatted + " " + meta.unit();
        }
        return display(value);
    }

    private String decimal(BigDecimal value, int scale) {
        return new DecimalFormat("#,##0.00").format(value.setScale(scale, RoundingMode.HALF_UP));
    }

    private String display(Object value) {
        if (value == null) {
            return "—";
        }
        if (value instanceof BigDecimal bd) {
            return bd.stripTrailingZeros().toPlainString();
        }
        return String.valueOf(value);
    }

    private String metricLabel(Map<String, MetricMeta> metrics, Map<String, ColumnMeta> columns, String key) {
        MetricMeta metric = metrics.get(key);
        return metric != null ? metric.label() : label(columns, key);
    }

    private MetricMeta meta(Map<String, MetricMeta> metrics, String key) {
        return metrics.getOrDefault(key, new MetricMeta(key, "", "number"));
    }

    private String label(Map<String, ColumnMeta> columns, String key) {
        ColumnMeta meta = columns.get(key);
        return meta == null ? key : meta.label();
    }

    private Map<String, MetricMeta> metricMeta(SemanticSnapshot snapshot) {
        Map<String, MetricMeta> result = new LinkedHashMap<>();
        for (SemMetric metric : snapshot.metrics()) {
            result.put(metric.getMetricCode(), new MetricMeta(metric.getMetricName(),
                    metric.getUnit() == null ? "" : metric.getUnit(),
                    metric.getValueFormat() == null ? "number" : metric.getValueFormat()));
        }
        return result;
    }

    private Map<String, ColumnMeta> columnMeta(SemanticSnapshot snapshot) {
        Map<String, ColumnMeta> result = new LinkedHashMap<>();
        for (SemTable table : snapshot.tables()) {
            for (SemColumn column : snapshot.columnsOf(table.getTableName())) {
                result.putIfAbsent(column.getColumnName(), new ColumnMeta(column.getBusinessName(),
                        column.getUnit() == null ? "" : column.getUnit()));
            }
        }
        return result;
    }

    private record MetricMeta(String label, String unit, String format) {
    }

    private record ColumnMeta(String label, String unit) {
    }
}
