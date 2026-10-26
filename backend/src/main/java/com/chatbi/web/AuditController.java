package com.chatbi.web;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chatbi.domain.entity.AppUser;
import com.chatbi.domain.entity.QueryAudit;
import com.chatbi.mapper.QueryAuditMapper;
import com.chatbi.service.CurrentUserService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/audit")
public class AuditController {

    private final QueryAuditMapper auditMapper;
    private final CurrentUserService currentUserService;
    private final JdbcTemplate jdbcTemplate;

    public AuditController(QueryAuditMapper auditMapper, CurrentUserService currentUserService,
                           JdbcTemplate jdbcTemplate) {
        this.auditMapper = auditMapper;
        this.currentUserService = currentUserService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping
    public List<QueryAudit> recent(@RequestParam(defaultValue = "50") int limit,
                                   @RequestParam(required = false) String status,
                                   @RequestParam(required = false) Long beforeId,
                                   @RequestParam(defaultValue = "false") boolean all) {
        AppUser user = currentUserService.getCurrent();
        if (all) {
            currentUserService.requireAdmin();
        }
        int safeLimit = Math.max(1, Math.min(200, limit));
        LambdaQueryWrapper<QueryAudit> query = new LambdaQueryWrapper<QueryAudit>()
                .orderByDesc(QueryAudit::getId).last("LIMIT " + safeLimit);
        if (beforeId != null && beforeId > 0) {
            query.lt(QueryAudit::getId, beforeId);
        }
        if (!(currentUserService.isAdmin() && all)) {
            query.eq(QueryAudit::getUserId, user.getUsername());
        }
        if (status != null && !status.isBlank()) {
            query.eq(QueryAudit::getStatus, status);
        }
        return auditMapper.selectList(query);
    }

    @GetMapping("/stats")
    public Map<String, Object> stats(@RequestParam(defaultValue = "7") int days,
                                     @RequestParam(defaultValue = "false") boolean all) {
        AppUser user = currentUserService.getCurrent();
        if (all) {
            currentUserService.requireAdmin();
        }
        int safeDays = Math.max(1, Math.min(90, days));
        boolean global = currentUserService.isAdmin() && all;
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE created_at >= now() - make_interval(days => ?)");
        args.add(safeDays);
        if (!global) {
            where.append(" AND user_id = ?");
            args.add(user.getUsername());
        }
        String sql = """
                SELECT COUNT(*) AS total,
                       COUNT(*) FILTER (WHERE status = 'OK') AS ok_count,
                       COUNT(*) FILTER (WHERE status = 'BLOCKED') AS blocked_count,
                       COUNT(*) FILTER (WHERE status = 'ERROR') AS error_count,
                       COUNT(*) FILTER (WHERE status = 'CLARIFY') AS clarify_count,
                       COALESCE(ROUND(AVG(elapsed_ms)), 0) AS avg_ms,
                       COALESCE(ROUND(AVG(elapsed_ms) FILTER (WHERE status = 'OK')), 0) AS avg_ok_ms,
                       COALESCE(PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY elapsed_ms), 0) AS p95_ms,
                       COALESCE(SUM(prompt_tokens), 0) AS prompt_tokens,
                       COALESCE(SUM(completion_tokens), 0) AS completion_tokens,
                       COALESCE(SUM(cost_usd), 0) AS cost_usd,
                       COALESCE(ROUND(AVG(row_count) FILTER (WHERE status = 'OK')), 0) AS avg_rows
                FROM query_audit
                """ + where;
        Map<String, Object> row = jdbcTemplate.queryForMap(sql, args.toArray());
        return Map.of("days", safeDays, "scope", global ? "all" : "self", "stats", row);
    }
}
