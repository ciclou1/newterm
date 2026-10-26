package com.chatbi.eval;

import com.chatbi.common.ChatBiException;
import com.chatbi.config.ChatBiProperties;
import com.chatbi.domain.entity.AppUser;
import com.chatbi.nl2sql.AgentResult;
import com.chatbi.nl2sql.Nl2SqlAgent;
import com.chatbi.nl2sql.PromptBuilder;
import com.chatbi.security.UserRoles;
import com.chatbi.service.UserDirectory;
import com.chatbi.sql.QueryExecutor;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Execution-accuracy evaluation. Every question is answered by the same agent
 * used by the product; its already-executed result set is compared with a
 * golden SQL result on the read-only pool. Rows are compared
 * order-insensitively, columns are matched by value rather than alias, and
 * numeric cells use a small relative tolerance.
 *
 * <p>Evaluation is restricted to admins: golden SQL is executed directly and
 * therefore does not go through row-level permission injection. Running it as a
 * restricted user would compare a permission-filtered actual result against an
 * unfiltered expected result and make the accuracy number meaningless.</p>
 */
@Slf4j
@Service
public class EvaluationService {

    private static final String DATASET = "eval/questions.json";
    private static final int MAX_JOBS = 20;

    private final Nl2SqlAgent agent;
    private final QueryExecutor queryExecutor;
    private final UserDirectory userDirectory;
    private final ObjectMapper objectMapper;
    private final ChatBiProperties properties;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, EvalJob> jobs = new ConcurrentHashMap<>();
    private final Map<String, QueryExecutor.QueryExecution> goldenCache = new ConcurrentHashMap<>();
    private volatile EvalDataset dataset;
    private volatile String datasetHash = "unknown";

    public EvaluationService(Nl2SqlAgent agent, QueryExecutor queryExecutor,
                             UserDirectory userDirectory, ObjectMapper objectMapper,
                             ChatBiProperties properties) {
        this.agent = agent;
        this.queryExecutor = queryExecutor;
        this.userDirectory = userDirectory;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public EvalDataset dataset() {
        EvalDataset current = dataset;
        if (current == null) {
            synchronized (this) {
                if (dataset == null) {
                    dataset = loadDataset();
                }
                current = dataset;
            }
        }
        return current;
    }

    public List<EvalJobView> recent() {
        return jobs.values().stream()
                .sorted(Comparator.comparing((EvalJob job) -> job.createdAt).reversed())
                .map(EvalJob::view)
                .toList();
    }

    public EvalJobView job(String id) {
        EvalJob job = jobs.get(id);
        return job == null ? null : job.view();
    }

    public EvalJobView start(String category, Integer limit, String username) {
        AppUser user = userDirectory.findByUsername(username);
        if (user == null || Boolean.FALSE.equals(user.getEnabled())) {
            throw new IllegalArgumentException("评测执行用户不存在或已禁用: " + username);
        }
        // Evaluation is admin-only by design: the golden SQL bypasses row-level
        // permissions, so any non-admin run would compare incomparable results.
        // Checked against the directory as well as the HTTP layer so the rule
        // also holds for any future non-HTTP caller.
        if (!UserRoles.hasRole(UserRoles.of(user), UserRoles.ADMIN)) {
            throw ChatBiException.forbidden("ADMIN_REQUIRED", "评测仅限平台管理员执行");
        }
        List<EvalQuestion> questions = dataset().questions().stream()
                .filter(q -> category == null || category.isBlank() || category.equals(q.category()))
                .limit(limit == null || limit <= 0 ? Long.MAX_VALUE : limit)
                .toList();
        if (questions.isEmpty()) {
            throw new IllegalArgumentException("没有匹配的评测题");
        }
        EvalJob job = new EvalJob(UUID.randomUUID().toString(), user, questions,
                model(), PromptBuilder.VERSION, dataset().version(), datasetHash(), resolveCommit());
        jobs.put(job.id, job);
        if (jobs.size() > MAX_JOBS) {
            jobs.values().stream()
                    .filter(EvalJob::finished)
                    .sorted(Comparator.comparing((EvalJob entry) -> entry.createdAt))
                    .limit(jobs.size() - MAX_JOBS)
                    .forEach(old -> jobs.remove(old.id));
        }
        executor.submit(() -> run(job));
        return job.view();
    }

    private void run(EvalJob job) {
        long started = System.currentTimeMillis();
        try {
            for (EvalQuestion question : job.questions) {
                CaseResult result = evaluate(question, job.user);
                job.add(result);
            }
            job.complete(null, System.currentTimeMillis() - started);
            log.info("Evaluation {} finished: {}/{} passed", job.id, job.passed(), job.total());
        } catch (Exception e) {
            log.error("Evaluation {} failed", job.id, e);
            job.complete(e.getMessage(), System.currentTimeMillis() - started);
        }
    }

    CaseResult evaluate(EvalQuestion question, AppUser user) {
        long start = System.currentTimeMillis();
        AgentResult answer;
        try {
            answer = agent.ask(question.question(), user, List.of());
        } catch (Exception e) {
            return CaseResult.failed(question, "ERROR", "AGENT_ERROR: " + e.getMessage(),
                    System.currentTimeMillis() - start);
        }
        long latency = System.currentTimeMillis() - start;
        String status = answer.status();
        String generatedSql = answer.finalSql() == null ? answer.generatedSql() : answer.finalSql();

        if ("CLARIFY".equals(question.expectedStatus())) {
            boolean passed = "CLARIFY".equals(status);
            return new CaseResult(question.id(), question.category(), question.difficulty(),
                    question.question(), question.expectedStatus(), status, passed,
                    passed ? "按预期发起口径澄清" : "期望澄清，实际状态 " + status,
                    question.goldenSql(), generatedSql, 0, 0, latency, answer.mode(),
                    answer.failure() == null ? null : answer.failure().code());
        }

        if (!answer.ok()) {
            String detail = answer.failure() == null
                    ? "生成失败，状态 " + status
                    : answer.failure().code() + ": " + answer.failure().message();
            return new CaseResult(question.id(), question.category(), question.difficulty(),
                    question.question(), question.expectedStatus(), status, false,
                    "执行失败: " + detail, question.goldenSql(), generatedSql, 0, 0,
                    latency, answer.mode(), answer.failure() == null ? status : answer.failure().code());
        }

        QueryExecutor.QueryExecution expected = golden(question.goldenSql());
        // Reuse the result set the agent already produced. Re-executing the
        // generated SQL here would bypass the EXPLAIN cost gate and audit trail
        // and would double the execution cost of every evaluation case.
        QueryExecutor.QueryExecution actual = asExecution(answer);
        if (!expected.success()) {
            return new CaseResult(question.id(), question.category(), question.difficulty(),
                    question.question(), question.expectedStatus(), status, false,
                    "基准 SQL 执行失败: " + expected.error(), question.goldenSql(), generatedSql,
                    0, 0, latency, answer.mode(), "GOLDEN_SQL_ERROR");
        }
        if (!actual.success()) {
            return new CaseResult(question.id(), question.category(), question.difficulty(),
                    question.question(), question.expectedStatus(), status, false,
                    "生成 SQL 未返回可比较的结果集: " + actual.error(), question.goldenSql(), generatedSql,
                    expected.rows().size(), 0, latency, answer.mode(), "GENERATED_SQL_ERROR");
        }
        Comparison comparison = compare(expected, actual);
        return new CaseResult(question.id(), question.category(), question.difficulty(),
                question.question(), question.expectedStatus(), status, comparison.matched(),
                comparison.message(), question.goldenSql(), generatedSql,
                expected.rows().size(), actual.rows().size(), latency, answer.mode(),
                comparison.matched() ? null : "RESULT_MISMATCH");
    }

    private QueryExecutor.QueryExecution golden(String sql) {
        return goldenCache.computeIfAbsent(sql, queryExecutor::execute);
    }

    private QueryExecutor.QueryExecution asExecution(AgentResult answer) {
        AgentResult.QueryResult result = answer.result();
        if (result == null) {
            return new QueryExecutor.QueryExecution(false, List.of(), List.of(), false, false, 0,
                    "Agent 未返回结果集");
        }
        return new QueryExecutor.QueryExecution(true, result.columns(), result.rows(),
                !result.rows().isEmpty(), result.truncated(), 0, null);
    }

    private String model() {
        return properties.getAi().isEnabled() ? properties.getAi().getModel() : "demo-rule-engine";
    }

    private String datasetHash() {
        dataset();
        return datasetHash;
    }

    private String resolveCommit() {
        for (String candidate : new String[]{
                System.getProperty("chatbi.commit"),
                System.getenv("GIT_COMMIT"),
                System.getenv("GITHUB_SHA")}) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return "unknown";
    }

    private Comparison compare(QueryExecutor.QueryExecution expected, QueryExecutor.QueryExecution actual) {
        if (expected.rows().size() != actual.rows().size()) {
            return new Comparison(false, "行数不一致: 期望 " + expected.rows().size()
                    + "，实际 " + actual.rows().size());
        }
        List<String> expectedColumns = expected.columns().stream().map(QueryExecutor.ResultColumn::name).toList();
        List<String> actualColumns = actual.columns().stream().map(QueryExecutor.ResultColumn::name).toList();
        if (expectedColumns.size() != actualColumns.size()) {
            return new Comparison(false, "列数不一致: 期望 " + expectedColumns.size()
                    + "，实际 " + actualColumns.size());
        }
        if (expectedColumns.isEmpty()) {
            return new Comparison(true, "两侧均无列");
        }

        List<List<String>> expectedCells = cells(expected, expectedColumns);
        List<List<String>> actualCells = cells(actual, actualColumns);

        List<Integer> mapping = new ArrayList<>();
        Set<Integer> used = new LinkedHashSet<>();
        for (int e = 0; e < expectedColumns.size(); e++) {
            int matched = -1;
            for (int a = 0; a < actualColumns.size(); a++) {
                if (!used.contains(a) && sameColumn(expectedCells.get(e), actualCells.get(a))) {
                    matched = a;
                    break;
                }
            }
            if (matched < 0) {
                int sameName = sameNameColumn(actualColumns, used, expectedColumns.get(e));
                if (sameName >= 0) {
                    return new Comparison(false, "列 " + expectedColumns.get(e) + " 值不匹配（期望 "
                            + preview(expectedCells.get(e)) + "，实际 "
                            + preview(actualCells.get(sameName)) + "）");
                }
                return new Comparison(false, "列 " + expectedColumns.get(e) + " 无法匹配生成结果列 "
                        + actualColumns);
            }
            used.add(matched);
            mapping.add(matched);
        }

        List<String> expectedKeys = new ArrayList<>();
        List<String> actualKeys = new ArrayList<>();
        for (int row = 0; row < expectedCells.get(0).size(); row++) {
            List<String> e = new ArrayList<>();
            List<String> a = new ArrayList<>();
            for (int col = 0; col < expectedColumns.size(); col++) {
                e.add(expectedCells.get(col).get(row));
                a.add(actualCells.get(mapping.get(col)).get(row));
            }
            expectedKeys.add(String.join("\u0001", e));
            actualKeys.add(String.join("\u0001", a));
        }
        Collections.sort(expectedKeys);
        Collections.sort(actualKeys);
        for (int i = 0; i < expectedKeys.size(); i++) {
            if (!expectedKeys.get(i).equals(actualKeys.get(i))) {
                return new Comparison(false, "第 " + (i + 1) + " 行数值不一致");
            }
        }
        return new Comparison(true, "结果一致");
    }

    private List<List<String>> cells(QueryExecutor.QueryExecution execution, List<String> columns) {
        List<List<String>> values = new ArrayList<>();
        for (String column : columns) {
            List<String> columnValues = new ArrayList<>();
            for (Map<String, Object> row : execution.rows()) {
                columnValues.add(canonical(row.get(column), 6));
            }
            values.add(columnValues);
        }
        return values;
    }

    /**
     * Finds an unused actual column whose alias matches the expected column.
     * Used only to explain a value mismatch better; column matching itself
     * stays value-based.
     */
    private int sameNameColumn(List<String> actualColumns, Set<Integer> used, String expectedName) {
        for (int a = 0; a < actualColumns.size(); a++) {
            String actualName = actualColumns.get(a);
            if (!used.contains(a) && expectedName != null && actualName != null
                    && expectedName.trim().equalsIgnoreCase(actualName.trim())) {
                return a;
            }
        }
        return -1;
    }

    private String preview(List<String> values) {
        if (values.isEmpty()) {
            return "空";
        }
        int shown = Math.min(3, values.size());
        List<String> parts = values.stream().limit(shown).map(this::previewValue).toList();
        String joined = String.join("、", parts);
        return values.size() > shown ? joined + "…（共 " + values.size() + " 行）" : joined;
    }

    private String previewValue(String value) {
        if ("\u0000NULL".equals(value) || value == null) {
            return "NULL";
        }
        return value.length() > 40 ? value.substring(0, 40) + "…" : value;
    }

    private boolean sameColumn(List<String> left, List<String> right) {
        if (left.size() != right.size()) {
            return false;
        }
        List<String> a = left.stream().map(v -> canonical(v, 2)).sorted().toList();
        List<String> b = right.stream().map(v -> canonical(v, 2)).sorted().toList();
        return a.equals(b);
    }

    private String canonical(Object value, int scale) {
        if (value == null) {
            return "\u0000NULL";
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue())
                    .setScale(scale, RoundingMode.HALF_UP)
                    .stripTrailingZeros().toPlainString();
        }
        return String.valueOf(value).trim();
    }

    private EvalDataset loadDataset() {
        try (InputStream input = new ClassPathResource(DATASET).getInputStream()) {
            byte[] bytes = input.readAllBytes();
            datasetHash = sha256(bytes);
            EvalDataset loaded = objectMapper.readValue(bytes, EvalDataset.class);
            log.info("Loaded {} evaluation questions from {} (sha256={})",
                    loaded.questions().size(), DATASET, datasetHash);
            return loaded;
        } catch (IOException e) {
            throw new IllegalStateException("无法加载评测集 " + DATASET, e);
        }
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
    }

    private record Comparison(boolean matched, String message) {
    }

    public record EvalQuestion(
            @JsonProperty("id") int id,
            @JsonProperty("category") String category,
            @JsonProperty("difficulty") String difficulty,
            @JsonProperty("question") String question,
            @JsonProperty("expectedStatus") String expectedStatus,
            @JsonProperty("goldenSql") String goldenSql,
            @JsonProperty("tables") List<String> tables) {
    }

    public record EvalDataset(
            @JsonProperty("version") String version,
            @JsonProperty("description") String description,
            @JsonProperty("questions") List<EvalQuestion> questions) {
    }

    public record CaseResult(int id, String category, String difficulty, String question,
                             String expectedStatus, String status, boolean passed, String message,
                             String goldenSql, String generatedSql, int expectedRows, int actualRows,
                             long latencyMs, String mode, String code) {
        static CaseResult failed(EvalQuestion question, String status, String message, long latency) {
            return new CaseResult(question.id(), question.category(), question.difficulty(),
                    question.question(), question.expectedStatus(), status, false, message,
                    question.goldenSql(), null, 0, 0, latency, null, "ERROR");
        }
    }

    public record CategoryStat(int total, int passed, double accuracy) {
    }

    public record EvalJobView(String id, String status, String user, String model,
                              String promptVersion, String datasetVersion, String datasetHash,
                              String commit, String createdAt, String finishedAt,
                              int total, int completed, int passed, double accuracy,
                              Map<String, CategoryStat> categories, String error,
                              List<CaseResult> results) {
    }

    private static final class EvalJob {
        private final String id;
        private final AppUser user;
        private final List<EvalQuestion> questions;
        private final String model;
        private final String promptVersion;
        private final String datasetVersion;
        private final String datasetHash;
        private final String commit;
        private final List<CaseResult> results = new ArrayList<>();
        private final OffsetDateTime createdAt = OffsetDateTime.now();
        private final AtomicInteger passed = new AtomicInteger();
        private volatile String status = "RUNNING";
        private volatile OffsetDateTime finishedAt;
        private volatile String error;

        private EvalJob(String id, AppUser user, List<EvalQuestion> questions, String model,
                        String promptVersion, String datasetVersion, String datasetHash, String commit) {
            this.id = id;
            this.user = user;
            this.questions = questions;
            this.model = model;
            this.promptVersion = promptVersion;
            this.datasetVersion = datasetVersion;
            this.datasetHash = datasetHash;
            this.commit = commit;
        }

        private void add(CaseResult result) {
            synchronized (results) {
                results.add(result);
                if (result.passed()) {
                    passed.incrementAndGet();
                }
            }
        }

        private void complete(String error, long elapsedMs) {
            this.error = error;
            this.finishedAt = OffsetDateTime.now();
            this.status = error == null ? "COMPLETED" : "FAILED";
        }

        private boolean finished() {
            return finishedAt != null;
        }

        private int total() {
            return results.size();
        }

        private int passed() {
            return passed.get();
        }

        private EvalJobView view() {
            List<CaseResult> snapshot;
            synchronized (results) {
                snapshot = List.copyOf(results);
            }
            Map<String, int[]> counts = new LinkedHashMap<>();
            for (CaseResult result : snapshot) {
                int[] stat = counts.computeIfAbsent(result.category(), k -> new int[2]);
                stat[0]++;
                if (result.passed()) {
                    stat[1]++;
                }
            }
            Map<String, CategoryStat> categories = new LinkedHashMap<>();
            counts.forEach((category, stat) -> categories.put(category,
                    new CategoryStat(stat[0], stat[1], ratio(stat[1], stat[0]))));
            return new EvalJobView(id, status, user.getUsername(), model, promptVersion,
                    datasetVersion, datasetHash, commit,
                    createdAt.toString(),
                    finishedAt == null ? null : finishedAt.toString(), questions.size(),
                    snapshot.size(), passed.get(), ratio(passed.get(), snapshot.size()),
                    categories, error, snapshot);
        }

        private static double ratio(int passed, int total) {
            return total == 0 ? 0 : Math.round(10000.0 * passed / total) / 100.0;
        }
    }
}
