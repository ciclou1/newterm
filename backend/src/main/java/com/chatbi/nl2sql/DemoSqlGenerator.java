package com.chatbi.nl2sql;

import com.chatbi.domain.entity.SemColumn;
import com.chatbi.domain.entity.SemEnum;
import com.chatbi.domain.entity.SemMetric;
import com.chatbi.domain.entity.SemTable;
import com.chatbi.linking.SchemaContext;
import com.chatbi.linking.TextTokenizer;
import com.chatbi.semantic.SemanticSnapshot;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic offline planner used when no LLM key is configured. It is a
 * real NL2SQL baseline over the semantic layer: metric synonym resolution,
 * time-range parsing, dimension grouping, enum filters and TopN generation.
 * It deliberately produces the same SQL contract as the AI path.
 */
@Component
public class DemoSqlGenerator {

    private static final Pattern TOP_N = Pattern.compile("(?:前|top)\\s*(\\d+)|(?:最高|最低|最多|最少|最大|最小)的?\\s*(\\d+)");
    private static final Pattern NEAR_MONTHS = Pattern.compile("(?:近|最近|过去)(\\d+)个?月");
    private static final Pattern NEAR_DAYS = Pattern.compile("(?:近|最近|过去)(\\d+)天");
    private static final Pattern EXPLICIT_MONTH = Pattern.compile("(20\\d{2})年(\\d{1,2})月");
    private static final Pattern EXPLICIT_YEAR = Pattern.compile("(20\\d{2})年");
    private static final Pattern EXPLICIT_QUARTER = Pattern.compile("(20\\d{2})年?第?([1-4一二三四])季度");
    private static final Pattern Q_QUARTER = Pattern.compile("(20\\d{2})年?[qQ]([1-4])");
    private static final Pattern GROUP_BY = Pattern.compile(
            "(?is)\\bGROUP\\s+BY\\s+(.+?)(?=\\bORDER\\s+BY\\b|\\bHAVING\\b|\\bLIMIT\\b|\\bOFFSET\\b|$)");
    private static final Pattern SIMPLE_IDENTIFIER = Pattern.compile(
            "[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)?");

    /**
     * Phrases that explicitly ask for a single aggregate row. They suppress
     * dimension inheritance so "那今年总额呢" does not silently keep grouping.
     */
    private static final List<String> AGGREGATE_ONLY_MARKERS = List.of(
            "总额", "总计", "合计", "总共", "一共", "整体", "汇总", "不分组", "不分区域");

    private final com.chatbi.semantic.SemanticCatalog catalog;
    private final TextTokenizer tokenizer;

    public DemoSqlGenerator(com.chatbi.semantic.SemanticCatalog catalog, TextTokenizer tokenizer) {
        this.catalog = catalog;
        this.tokenizer = tokenizer;
    }

    private SemanticSnapshot snapshot() {
        return catalog.snapshot();
    }

    public SqlGeneration generate(String question, SchemaContext schema, String previousQuestion) {
        return generate(question, schema, previousQuestion, null);
    }

    /**
     * @param previousSql the SQL actually executed for the previous turn, used
     *                    to recover its GROUP BY when the follow-up is
     *                    elliptical ("那去年呢"). May be null.
     */
    public SqlGeneration generate(String question, SchemaContext schema, String previousQuestion,
                                  String previousSql) {
        String current = question == null ? "" : question.trim();
        String previous = previousQuestion == null ? "" : previousQuestion.trim();
        String normalized = tokenizer.normalize(current);
        String previousNormalized = tokenizer.normalize(previous);

        List<MetricMatch> metricMatches = matchMetrics(normalized, true);
        boolean metricInherited = false;
        String metricScope = normalized;
        if (metricMatches.isEmpty() && !previousNormalized.isBlank()) {
            metricMatches = matchMetrics(previousNormalized, false);
            metricScope = previousNormalized;
            metricInherited = !metricMatches.isEmpty();
        }
        if (metricMatches.isEmpty()) {
            return clarificationForMissingMetric(normalized, previousNormalized);
        }

        String primaryBase = metricMatches.getFirst().metric().getBaseTable();
        boolean mixedBases = metricMatches.stream().anyMatch(m -> !m.metric().getBaseTable().equals(primaryBase));
        if (mixedBases) {
            List<SqlGeneration.Clarification.Option> options = metricMatches.stream()
                    .limit(4)
                    .map(m -> new SqlGeneration.Clarification.Option(
                            m.metric().getMetricName(),
                            "按" + m.metric().getMetricName() + "统计，其它条件不变"))
                    .toList();
            return SqlGeneration.clarify(new SqlGeneration.Clarification(
                    "这个问题涉及多个事实表，当前离线模式一次只能分析一个主题，请选择指标口径", options), "DEMO");
        }

        SemTable primaryTable = snapshot().table(primaryBase).orElse(null);
        if (primaryTable == null) {
            return SqlGeneration.error("语义层缺少指标所属表 " + primaryBase, "DEMO", 0, 0, null);
        }

        List<MetricMatch> selectedMetrics = metricMatches.stream()
                .filter(m -> m.metric().getBaseTable().equals(primaryBase))
                .limit(4)
                .toList();

        boolean currentHasFilter = hasEnumLabel(normalized, primaryBase);
        String filterScope = currentHasFilter || previousNormalized.isBlank() ? normalized : previousNormalized;
        String timeScope = hasTimeMarker(normalized) || previousNormalized.isBlank() ? normalized : previousNormalized;

        List<DimensionMatch> dimensions = selectDimensions(normalized, primaryTable, selectedMetrics);
        if (dimensions.isEmpty() && !previousNormalized.isBlank() && !requestsAggregateOnly(normalized)
                && (metricInherited || isEllipticalFollowUp(normalized))) {
            // Keep the previous turn's GROUP BY for a shortened follow-up
            // ("今年各区域销售额" -> "那去年呢"), but only rewrite what the
            // user changed (here: the time range).
            dimensions = inheritDimensions(previousSql, previousNormalized, primaryTable);
        }
        TimeRange timeRange = detectTimeRange(timeScope, selectedMetrics.getFirst().metric().getTimeColumn());
        List<EnumFilter> filters = selectEnumFilters(filterScope, primaryTable, dimensions, selectedMetrics);
        TopN topN = detectTopN(normalized, !dimensions.isEmpty());
        String sql = buildSql(primaryTable, selectedMetrics, dimensions, filters, timeRange, topN);

        String explanation = buildExplanation(selectedMetrics, dimensions, filters, timeRange, topN);
        return SqlGeneration.demo(sql, explanation);
    }

    private List<MetricMatch> matchMetrics(String question, boolean fromCurrent) {
        if (question.isBlank()) {
            return List.of();
        }
        List<AliasMatch> candidates = new ArrayList<>();
        SemanticSnapshot snapshot = snapshot();
        for (SemMetric metric : snapshot.metrics()) {
            for (String alias : metricAliases(metric)) {
                String normalizedAlias = tokenizer.normalize(alias);
                if (normalizedAlias.length() < 2) {
                    continue;
                }
                int position = question.indexOf(normalizedAlias);
                if (position >= 0) {
                    candidates.add(new AliasMatch(metric, alias, position,
                            position + normalizedAlias.length(), normalizedAlias.length()));
                }
            }
        }
        // Prefer the earliest, longest, non-overlapping synonym. This resolves
        // 毛利率 vs 毛利, 客户等级 vs 客户, 逾期应收 vs 应收.
        candidates.sort(Comparator.comparingInt(AliasMatch::start)
                .thenComparing(Comparator.comparingInt(AliasMatch::length).reversed()));
        List<AliasMatch> chosen = new ArrayList<>();
        for (AliasMatch candidate : candidates) {
            boolean overlaps = chosen.stream().anyMatch(c -> candidate.start() < c.end() && candidate.end() > c.start());
            if (!overlaps) {
                chosen.add(candidate);
            }
        }
        Map<String, MetricMatch> byCode = new LinkedHashMap<>();
        for (AliasMatch match : chosen) {
            byCode.putIfAbsent(match.metric().getMetricCode(),
                    new MetricMatch(match.metric(), match.alias(), match.start(), fromCurrent));
        }
        return byCode.values().stream()
                .sorted(Comparator.comparingInt(MetricMatch::position))
                .limit(4)
                .toList();
    }

    private List<DimensionMatch> selectDimensions(String question, SemTable table, List<MetricMatch> metrics) {
        if (!hasGroupIntent(question)) {
            return List.of();
        }
        List<AliasMatch> candidates = new ArrayList<>();
        SemanticSnapshot snapshot = snapshot();
        for (SemColumn column : snapshot.columnsOf(table.getTableName())) {
            if (!Boolean.TRUE.equals(column.getIsDimension())) {
                continue;
            }
            for (String alias : columnAliases(column, table.getTableName())) {
                String normalizedAlias = tokenizer.normalize(alias);
                if (normalizedAlias.length() < 2) {
                    continue;
                }
                int position = question.indexOf(normalizedAlias);
                if (position >= 0) {
                    int end = position + normalizedAlias.length();
                    if (overlapsMetric(position, end, metrics)) {
                        continue;
                    }
                    candidates.add(new AliasMatch(null, alias, position,
                            end, normalizedAlias.length(),
                            column.getColumnName()));
                }
            }
        }
        candidates.sort(Comparator.comparingInt(AliasMatch::length).reversed()
                .thenComparingInt(AliasMatch::start));
        List<AliasMatch> chosen = new ArrayList<>();
        for (AliasMatch candidate : candidates) {
            String column = candidate.column();
            if (chosen.stream().anyMatch(c -> c.column().equals(column))) {
                continue;
            }
            boolean overlaps = chosen.stream().anyMatch(c -> candidate.start() < c.end() && candidate.end() > c.start());
            if (!overlaps) {
                chosen.add(candidate);
            }
        }

        // A "trend" phrase without an explicit month dimension still needs the
        // semantic month column to produce a line chart.
        if (chosen.isEmpty() && (question.contains("趋势") || question.contains("按月") || question.contains("每月"))) {
            monthColumn(table).ifPresent(column -> chosen.add(new AliasMatch(null, "月份", 0, 2, 2,
                    column.getColumnName())));
        }

        chosen.sort(Comparator.comparingInt(AliasMatch::start));
        List<DimensionMatch> dimensions = new ArrayList<>();
        for (AliasMatch match : chosen.stream().limit(2).toList()) {
            snapshot.columnsOf(table.getTableName()).stream()
                    .filter(c -> c.getColumnName().equals(match.column()))
                    .findFirst()
                    .ifPresent(c -> dimensions.add(new DimensionMatch(c.getColumnName(),
                            c.getBusinessName(), match.alias(), match.start())));
        }
        return dimensions;
    }

    /**
     * Recovers the grouping of the previous turn. The executed SQL is the most
     * faithful source (it already passed guard/row-permission rewriting); the
     * previous question is a fallback for callers that only kept the text.
     */
    private List<DimensionMatch> inheritDimensions(String previousSql, String previousQuestion, SemTable table) {
        List<DimensionMatch> fromSql = dimensionsFromSql(previousSql, table);
        if (!fromSql.isEmpty()) {
            return fromSql;
        }
        return selectDimensions(previousQuestion, table, List.of());
    }

    private List<DimensionMatch> dimensionsFromSql(String sql, SemTable table) {
        if (sql == null || sql.isBlank()) {
            return List.of();
        }
        Matcher matcher = GROUP_BY.matcher(sql);
        if (!matcher.find()) {
            return List.of();
        }
        List<DimensionMatch> dimensions = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String raw : matcher.group(1).split(",")) {
            String token = raw.trim();
            if (!SIMPLE_IDENTIFIER.matcher(token).matches()) {
                // Ordinal positions or expressions cannot be mapped back to a
                // semantic dimension; fall back to the previous question.
                return List.of();
            }
            String columnName = token.contains(".") ? token.substring(token.lastIndexOf('.') + 1) : token;
            if (!seen.add(columnName)) {
                continue;
            }
            snapshot().columnsOf(table.getTableName()).stream()
                    .filter(c -> Boolean.TRUE.equals(c.getIsDimension()))
                    .filter(c -> c.getColumnName().equalsIgnoreCase(columnName))
                    .findFirst()
                    .ifPresent(c -> dimensions.add(new DimensionMatch(c.getColumnName(),
                            c.getBusinessName(), c.getBusinessName(), 0)));
            if (dimensions.size() >= 2) {
                break;
            }
        }
        return dimensions;
    }

    private boolean requestsAggregateOnly(String question) {
        return AGGREGATE_ONLY_MARKERS.stream().anyMatch(question::contains);
    }

    /**
     * True for short context-dependent phrasings such as "那去年呢" or
     * "那订单数呢" that are not self-contained questions.
     */
    private boolean isEllipticalFollowUp(String question) {
        if (question.length() > 24) {
            return false;
        }
        return question.startsWith("那") || question.endsWith("呢") || question.endsWith("呢？")
                || question.contains("刚才") || question.contains("同样") || question.contains("还是");
    }

    private List<EnumFilter> selectEnumFilters(String question, SemTable table, List<DimensionMatch> dimensions,
                                               List<MetricMatch> metrics) {
        Set<String> groupedColumns = new HashSet<>();
        dimensions.forEach(d -> groupedColumns.add(d.column()));
        Map<String, LinkedHashSet<String>> valuesByColumn = new LinkedHashMap<>();
        SemanticSnapshot snapshot = snapshot();
        for (SemEnum e : snapshot.enumsOf(table.getTableName())) {
            if (groupedColumns.contains(e.getColumnName())) {
                continue;
            }
            String label = tokenizer.normalize(e.getEnumLabel());
            int position = label.length() >= 2 ? question.indexOf(label) : -1;
            if (position >= 0 && !overlapsMetric(position, position + label.length(), metrics)) {
                valuesByColumn.computeIfAbsent(e.getColumnName(), k -> new LinkedHashSet<>())
                        .add(e.getEnumValue());
            }
        }
        List<EnumFilter> filters = new ArrayList<>();
        valuesByColumn.forEach((column, values) -> filters.add(new EnumFilter(column, List.copyOf(values))));
        return filters;
    }

    private boolean overlapsMetric(int start, int end, List<MetricMatch> metrics) {
        for (MetricMatch metric : metrics) {
            if (!metric.fromCurrent()) {
                continue;
            }
            int metricStart = metric.position();
            int metricEnd = metricStart + tokenizer.normalize(metric.alias()).length();
            if (start < metricEnd && end > metricStart) {
                return true;
            }
        }
        return false;
    }

    private TimeRange detectTimeRange(String question, String timeColumn) {
        if (timeColumn == null || question.isBlank()) {
            return null;
        }
        String q = question;
        Matcher explicitMonth = EXPLICIT_MONTH.matcher(q);
        if (explicitMonth.find()) {
            int year = Integer.parseInt(explicitMonth.group(1));
            int month = Integer.parseInt(explicitMonth.group(2));
            String start = String.format("%04d-%02d-01", year, month);
            String end = month == 12 ? String.format("%04d-01-01", year + 1) : String.format("%04d-%02d-01", year, month + 1);
            return new TimeRange(timeColumn + " >= DATE '" + start + "' AND " + timeColumn + " < DATE '" + end + "'",
                    year + "年" + month + "月");
        }
        Matcher qQuarter = Q_QUARTER.matcher(q);
        Matcher cnQuarter = EXPLICIT_QUARTER.matcher(q);
        if (qQuarter.find() || cnQuarter.find()) {
            Matcher m = qQuarter.find(0) ? qQuarter : cnQuarter;
            int year = Integer.parseInt(m.group(1));
            int quarter = parseQuarter(m.group(2));
            String start = String.format("%04d-%02d-01", year, (quarter - 1) * 3 + 1);
            int endMonth = quarter * 3;
            String end = String.format("%04d-%02d-01", endMonth == 12 ? year + 1 : year, endMonth == 12 ? 1 : endMonth + 1);
            return new TimeRange(timeColumn + " >= DATE '" + start + "' AND " + timeColumn + " < DATE '" + end + "'",
                    year + "年第" + quarter + "季度");
        }
        Matcher nearMonths = NEAR_MONTHS.matcher(q);
        if (nearMonths.find()) {
            int months = Integer.parseInt(nearMonths.group(1));
            return new TimeRange(timeColumn + " >= CURRENT_DATE - INTERVAL '" + months + " months'",
                    "近" + months + "个月");
        }
        Matcher nearDays = NEAR_DAYS.matcher(q);
        if (nearDays.find()) {
            int days = Integer.parseInt(nearDays.group(1));
            return new TimeRange(timeColumn + " >= CURRENT_DATE - INTERVAL '" + days + " days'", "近" + days + "天");
        }
        if (q.contains("上月") || q.contains("上个月")) {
            return new TimeRange(timeColumn + " >= date_trunc('month', CURRENT_DATE) - INTERVAL '1 month' AND "
                    + timeColumn + " < date_trunc('month', CURRENT_DATE)", "上月");
        }
        if (q.contains("本月") || q.contains("这个月")) {
            return new TimeRange(timeColumn + " >= date_trunc('month', CURRENT_DATE)", "本月");
        }
        if (q.contains("去年") || q.contains("上一年")) {
            return new TimeRange(timeColumn + " >= date_trunc('year', CURRENT_DATE) - INTERVAL '1 year' AND "
                    + timeColumn + " < date_trunc('year', CURRENT_DATE)", "去年");
        }
        if (q.contains("今年") || q.contains("本年度")) {
            return new TimeRange(timeColumn + " >= date_trunc('year', CURRENT_DATE)", "今年");
        }
        Matcher explicitYear = EXPLICIT_YEAR.matcher(q);
        if (explicitYear.find()) {
            int year = Integer.parseInt(explicitYear.group(1));
            return new TimeRange(timeColumn + " >= DATE '" + year + "-01-01' AND " + timeColumn
                    + " < DATE '" + (year + 1) + "-01-01'", year + "年");
        }
        return null;
    }

    private TopN detectTopN(String question, boolean hasGroup) {
        if (!hasGroup) {
            return null;
        }
        boolean descending = !(question.contains("最低") || question.contains("最少") || question.contains("最小")
                || question.contains("bottom"));
        Matcher matcher = TOP_N.matcher(question);
        if (matcher.find()) {
            String number = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            return new TopN(Math.min(100, Math.max(1, Integer.parseInt(number))), descending);
        }
        if (question.contains("排名") || question.contains("排行")) {
            return new TopN(10, descending);
        }
        if (question.contains("最高") || question.contains("最多") || question.contains("最大")) {
            return new TopN(10, true);
        }
        if (question.contains("最低") || question.contains("最少") || question.contains("最小")) {
            return new TopN(10, false);
        }
        return null;
    }

    private String buildSql(SemTable table, List<MetricMatch> metrics, List<DimensionMatch> dimensions,
                            List<EnumFilter> filters, TimeRange timeRange, TopN topN) {
        LinkedHashMap<String, String> selectParts = new LinkedHashMap<>();
        for (DimensionMatch dimension : dimensions) {
            selectParts.put(dimension.column(), dimension.column());
        }
        for (MetricMatch metric : metrics) {
            selectParts.put(metric.metric().getMetricCode(),
                    metric.metric().getExpression() + " AS " + metric.metric().getMetricCode());
        }

        List<String> where = new ArrayList<>();
        if (timeRange != null) {
            where.add("(" + timeRange.condition() + ")");
        }
        for (EnumFilter filter : filters) {
            if (filter.values().size() == 1) {
                where.add(filter.column() + " = " + quote(filter.values().getFirst()));
            } else {
                where.add(filter.column() + " IN (" + filter.values().stream().map(this::quote)
                        .collect(java.util.stream.Collectors.joining(", ")) + ")");
            }
        }

        StringBuilder sql = new StringBuilder("SELECT ")
                .append(String.join(", ", selectParts.values()))
                .append(" FROM ").append(table.getTableName());
        if (!where.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", where));
        }
        if (!dimensions.isEmpty()) {
            sql.append(" GROUP BY ")
                    .append(dimensions.stream().map(DimensionMatch::column)
                            .collect(java.util.stream.Collectors.joining(", ")));
        }
        if (!dimensions.isEmpty()) {
            String metricAlias = metrics.getFirst().metric().getMetricCode();
            boolean timeDimension = dimensions.size() == 1 && isMonthColumn(dimensions.getFirst().column());
            if (timeDimension) {
                sql.append(" ORDER BY ").append(dimensions.getFirst().column()).append(" ASC");
            } else if (topN != null) {
                sql.append(" ORDER BY ").append(metricAlias).append(topN.descending() ? " DESC" : " ASC");
            } else {
                sql.append(" ORDER BY ").append(metricAlias).append(" DESC");
            }
        }
        if (topN != null && !dimensions.isEmpty()) {
            sql.append(" LIMIT ").append(topN.limit());
        }
        return sql.toString();
    }

    private String buildExplanation(List<MetricMatch> metrics, List<DimensionMatch> dimensions,
                                    List<EnumFilter> filters, TimeRange timeRange, TopN topN) {
        StringBuilder sb = new StringBuilder("按");
        sb.append(metrics.stream().map(m -> "「" + m.metric().getMetricName() + "」")
                .collect(java.util.stream.Collectors.joining("、"))).append("口径");
        if (!dimensions.isEmpty()) {
            sb.append("，按").append(dimensions.stream().map(DimensionMatch::businessName)
                    .collect(java.util.stream.Collectors.joining("、"))).append("分组");
        }
        if (timeRange != null) {
            sb.append("，时间范围：").append(timeRange.label());
        }
        if (!filters.isEmpty()) {
            sb.append("，筛选：").append(filters.stream().map(f -> f.column() + "=" + String.join("/", f.values()))
                    .collect(java.util.stream.Collectors.joining("、")));
        }
        if (topN != null) {
            sb.append("，取").append(topN.descending() ? "前" : "后").append(topN.limit()).append("名");
        }
        return sb.append("。").toString();
    }

    private SqlGeneration clarificationForMissingMetric(String current, String previous) {
        String scope = current + " " + previous;
        String tableHint = inferTable(scope);
        List<SqlGeneration.Clarification.Option> options = new ArrayList<>();
        if (tableHint != null) {
            defaultMetricForTable(tableHint).ifPresent(metric -> options.add(
                    new SqlGeneration.Clarification.Option(metric.getMetricName(),
                            "按" + metric.getMetricName() + "统计")));
        }
        List<SemMetric> suggested = snapshot().metrics().stream()
                .filter(m -> List.of("sales_amount", "order_count", "purchase_amount", "maintenance_cost",
                        "inventory_value", "receivable_balance").contains(m.getMetricCode()))
                .toList();
        for (SemMetric metric : suggested) {
            if (options.size() >= 4) {
                break;
            }
            if (options.stream().noneMatch(o -> o.label().equals(metric.getMetricName()))) {
                options.add(new SqlGeneration.Clarification.Option(metric.getMetricName(),
                        "按" + metric.getMetricName() + "统计"));
            }
        }
        return SqlGeneration.clarify(new SqlGeneration.Clarification(
                "我还不确定你想看的口径，请选择一个指标；也可以直接补充时间范围或分组维度。", options), "DEMO");
    }

    private String inferTable(String question) {
        Map<String, List<String>> keywords = new LinkedHashMap<>();
        keywords.put("v_sales_fact", List.of("销售", "订单", "客户", "营收", "业绩"));
        keywords.put("v_purchase_fact", List.of("采购", "供应商"));
        keywords.put("v_inventory_snapshot", List.of("库存", "仓库", "缺货"));
        keywords.put("v_maintenance_fact", List.of("维修", "停机", "工单", "运维"));
        keywords.put("v_receivable_fact", List.of("应收", "回款", "账款", "发票"));
        keywords.put("v_equipment_dim", List.of("设备台账", "设备"));
        String best = null;
        int bestPos = Integer.MAX_VALUE;
        for (Map.Entry<String, List<String>> entry : keywords.entrySet()) {
            for (String keyword : entry.getValue()) {
                int pos = question.indexOf(keyword);
                if (pos >= 0 && pos < bestPos) {
                    bestPos = pos;
                    best = entry.getKey();
                }
            }
        }
        return best;
    }

    private Optional<SemMetric> defaultMetricForTable(String table) {
        String code = switch (table) {
            case "v_sales_fact" -> "sales_amount";
            case "v_purchase_fact" -> "purchase_amount";
            case "v_inventory_snapshot" -> "inventory_quantity";
            case "v_maintenance_fact" -> "maintenance_cost";
            case "v_receivable_fact" -> "receivable_balance";
            case "v_equipment_dim" -> "equipment_count";
            default -> null;
        };
        return code == null ? Optional.empty() : snapshot().metric(code);
    }

    private Optional<SemColumn> monthColumn(SemTable table) {
        String preferred = switch (table.getTableName()) {
            case "v_sales_fact", "v_purchase_fact" -> "order_month";
            case "v_maintenance_fact" -> "planned_month";
            case "v_receivable_fact" -> "invoice_month";
            default -> null;
        };
        if (preferred == null) {
            return Optional.empty();
        }
        return snapshot().columnsOf(table.getTableName()).stream()
                .filter(c -> c.getColumnName().equals(preferred)).findFirst();
    }

    private boolean hasGroupIntent(String question) {
        return question.contains("各") || question.contains("按") || question.contains("每")
                || question.contains("分布") || question.contains("趋势") || question.contains("排名")
                || question.contains("排行") || question.contains("top") || question.contains("前")
                || question.contains("最高") || question.contains("最低") || question.contains("最多")
                || question.contains("最少") || question.contains("不同");
    }

    private boolean hasTimeMarker(String question) {
        return question.contains("今年") || question.contains("去年") || question.contains("本月")
                || question.contains("上月") || question.contains("近") || question.contains("最近")
                || question.contains("过去") || question.matches(".*20\\d{2}年.*")
                || question.contains("季度") || question.contains("天");
    }

    private boolean hasEnumLabel(String question, String table) {
        return snapshot().enumsOf(table).stream()
                .anyMatch(e -> e.getEnumLabel().length() >= 2 && question.contains(tokenizer.normalize(e.getEnumLabel())));
    }

    private boolean isMonthColumn(String column) {
        return column.endsWith("_month") || column.equals("order_month");
    }

    private List<String> metricAliases(SemMetric metric) {
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

    private List<String> columnAliases(SemColumn column, String table) {
        Set<String> aliases = new LinkedHashSet<>();
        aliases.add(column.getBusinessName());
        snapshot().synonymsFor("COLUMN", table + "." + column.getColumnName())
                .forEach(s -> aliases.add(s.getSynonym()));
        switch (column.getColumnName()) {
            case "region" -> aliases.addAll(List.of("区域", "大区", "地区"));
            case "customer_name" -> aliases.addAll(List.of("客户", "买家"));
            case "product_name" -> aliases.addAll(List.of("产品", "商品"));
            case "category" -> aliases.addAll(List.of("产品类别", "品类", "类别"));
            case "department_name" -> aliases.addAll(List.of("部门", "销售部门", "采购部门"));
            case "salesperson" -> aliases.addAll(List.of("销售员", "销售人员"));
            case "industry" -> aliases.add("行业");
            case "customer_level" -> aliases.addAll(List.of("客户等级", "等级"));
            case "status" -> aliases.add("状态");
            case "supplier_name" -> aliases.add("供应商");
            case "supplier_category" -> aliases.add("供应商类别");
            case "warehouse_name" -> aliases.add("仓库");
            case "stock_status" -> aliases.add("库存状态");
            case "equipment_category" -> aliases.add("设备类别");
            case "maintenance_type" -> aliases.add("维修类型");
            case "criticality" -> aliases.add("关键等级");
            case "technician" -> aliases.addAll(List.of("维修人员", "工程师"));
            case "equipment_name" -> aliases.add("设备");
            case "order_month", "planned_month", "invoice_month" ->
                    aliases.addAll(List.of("月份", "月度", "按月", "每月", "各月"));
            default -> {
            }
        }
        return new ArrayList<>(aliases);
    }

    private int parseQuarter(String value) {
        return switch (value) {
            case "一", "1" -> 1;
            case "二", "2" -> 2;
            case "三", "3" -> 3;
            case "四", "4" -> 4;
            default -> 1;
        };
    }

    private String quote(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private record AliasMatch(SemMetric metric, String alias, int start, int end, int length, String column) {
        AliasMatch(SemMetric metric, String alias, int start, int end, int length) {
            this(metric, alias, start, end, length, null);
        }
    }

    private record MetricMatch(SemMetric metric, String alias, int position, boolean fromCurrent) {
    }

    private record DimensionMatch(String column, String businessName, String alias, int position) {
    }

    private record EnumFilter(String column, List<String> values) {
    }

    private record TimeRange(String condition, String label) {
    }

    private record TopN(int limit, boolean descending) {
    }
}
