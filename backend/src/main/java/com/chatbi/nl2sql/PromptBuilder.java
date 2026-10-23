package com.chatbi.nl2sql;

import com.chatbi.domain.entity.AppUser;
import com.chatbi.linking.SchemaContext;
import com.chatbi.linking.SchemaContext.ColumnDoc;
import com.chatbi.linking.SchemaContext.EnumDoc;
import com.chatbi.linking.SchemaContext.ExampleDoc;
import com.chatbi.linking.SchemaContext.MetricDoc;
import com.chatbi.linking.SchemaContext.TableDoc;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

@Component
public class PromptBuilder {

    public String systemPrompt(SchemaContext schema, AppUser user) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                你是企业级 ChatBI 的 NL2SQL 引擎，服务对象是 ERP/EAM 业务分析人员。
                你的任务：把中文问题转换为一条 PostgreSQL 只读 SELECT。

                强制规则：
                1. 只能使用“语义 Schema”中列出的表和字段，禁止臆造表名、字段名或指标。
                2. 只输出一条单层 SELECT；禁止 WITH/CTE、子查询、UNION、SELECT INTO、临时表。
                3. 禁止任何 DDL/DML、系统表、文件/网络/延时函数。
                4. 指标必须直接使用给定口径表达式；不要自行改变已完成/取消等业务过滤。
                5. 时间过滤使用 CURRENT_DATE / date_trunc 等 PostgreSQL 语法。
                6. 明细结果默认 LIMIT 200；聚合结果不强制加 LIMIT。TopN 问题必须 ORDER BY + LIMIT。
                7. 行级权限由平台执行前自动注入，禁止手写 department_id / tenant_id 过滤。
                8. 维度列使用物理字段名；指标用指标编码作为别名。
                9. 如果问题缺少指标、时间或维度导致存在多个合理口径，返回 clarification，不要猜测。
                10. 只输出 JSON，不要 Markdown 代码围栏，不要额外解释。

                输出 JSON 格式：
                {"sql":"SELECT ...","explanation":"一句话说明口径","confidence":0.0,"clarification":null}
                需要澄清时：
                {"sql":null,"explanation":null,"confidence":0.0,
                 "clarification":{"question":"请确认口径","options":[{"label":"销售额","question":"按销售额统计"}]}}

                当前日期：%s
                当前用户：%s（%s）
                """.formatted(LocalDate.now(), user.getDisplayName(), user.getRole()));

        sb.append("\n## 语义 Schema（仅这些表可用）\n");
        for (TableDoc table : schema.tables()) {
            sb.append("### ").append(table.tableName()).append(" · ").append(table.businessName())
                    .append(" [").append(table.domain()).append("]\n")
                    .append(trim(table.description(), 180)).append('\n');
            for (ColumnDoc column : table.columns()) {
                sb.append("- ").append(column.name()).append(" | ").append(column.businessName())
                        .append(" | ").append(column.dataType());
                if (column.dimension()) {
                    sb.append(" | 维度");
                }
                if (column.measure()) {
                    sb.append(" | 度量");
                }
                if (column.unit() != null && !column.unit().isBlank()) {
                    sb.append(" | ").append(column.unit());
                }
                if (column.description() != null && !column.description().isBlank()) {
                    sb.append(" | ").append(trim(column.description(), 70));
                }
                sb.append('\n');
            }
        }

        sb.append("\n## 指标口径（expression 可直接放入 SELECT）\n");
        for (MetricDoc metric : schema.metrics()) {
            sb.append("- ").append(metric.code()).append(" = ").append(metric.name())
                    .append("：").append(metric.expression())
                    .append("；base=").append(metric.baseTable());
            if (metric.timeColumn() != null) {
                sb.append("；time=").append(metric.timeColumn());
            }
            if (metric.unit() != null) {
                sb.append("；unit=").append(metric.unit());
            }
            sb.append("；").append(trim(metric.description(), 100)).append('\n');
        }

        if (!schema.enums().isEmpty()) {
            sb.append("\n## 枚举值（SQL 中必须使用 value 而非中文 label）\n");
            for (EnumDoc e : schema.enums()) {
                sb.append("- ").append(e.table()).append('.').append(e.column()).append(": ")
                        .append(e.label()).append(" = '").append(e.value()).append("'\n");
            }
        }

        if (!schema.examples().isEmpty()) {
            sb.append("\n## Few-shot 示例\n");
            for (ExampleDoc example : schema.examples()) {
                sb.append("Q: ").append(example.question()).append('\n')
                        .append("SQL: ").append(example.sql()).append("\n\n");
            }
        }
        return sb.toString();
    }

    public String userPrompt(String question, List<ConversationTurn> history,
                             String previousSql, String previousError) {
        StringBuilder sb = new StringBuilder();
        if (history != null && !history.isEmpty()) {
            sb.append("## 最近对话\n");
            history.stream().skip(Math.max(0, history.size() - 3)).forEach(turn -> {
                sb.append("用户：").append(turn.question()).append('\n');
                if (turn.sql() != null && !turn.sql().isBlank()) {
                    sb.append("已执行 SQL：").append(turn.sql()).append('\n');
                }
                if (turn.answer() != null && !turn.answer().isBlank()) {
                    sb.append("结论：").append(trim(turn.answer(), 200)).append('\n');
                }
            });
            sb.append('\n');
        }
        sb.append("## 当前问题\n").append(question).append('\n');
        if (previousSql != null && !previousSql.isBlank()) {
            sb.append("\n## 上一次生成但未通过的 SQL\n").append(previousSql).append('\n');
        }
        if (previousError != null && !previousError.isBlank()) {
            sb.append("\n## 校验/执行错误（必须修复）\n").append(previousError).append('\n')
                    .append("请仅输出修复后的 JSON。\n");
        }
        return sb.toString();
    }

    private String trim(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }
}
