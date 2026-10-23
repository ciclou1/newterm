# ChatBI · 企业数据智能问答平台

自然语言 → SQL → 图表与结论，面向 ERP / EAM 等结构化业务库的纯 Java 全栈 ChatBI（NL2SQL）演示项目。

- **后端**：JDK 21 虚拟线程 · Spring Boot 3.5 · Spring AI 1.1 · MyBatis-Plus · PostgreSQL 16 + pgvector · Redis · Druid
- **前端**：Vue 3.5 · TypeScript · Vite 7 · ECharts 5.6（亮/暗双主题）
- **交付**：Docker Compose 一键启动，另附 stdio MCP Server，可接入 Claude Desktop / Dify

---

## 1. 功能概览

| 模块 | 实现 |
|---|---|
| 语义层 | 6 个语义视图、99 个字段、20 个指标、32 组同义词、88 个枚举标签、20 条 Few-shot、行级权限，全部落库；后端提供维护 API，前端为只读查看（可刷新索引） |
| Schema Linking | 同义词扩展 + BM25 + 512 维哈希特征向量（pgvector，非语义 embedding）+ Few-shot 检索，先裁剪 Schema 再生成 SQL |
| NL2SQL Agent | 生成 → JSqlParser AST 校验 → 只读执行 → 错误回灌修复（最多 2 次）；结构违规可修复，写操作/系统表等硬策略直接拒绝 |
| SQL 安全 | 单层 SELECT（禁止 CTE/子查询/UNION/INTERSECT/EXCEPT）、表白名单、DDL/DML 拦截、危险函数黑名单、只读账号、EXPLAIN 代价预估、超时/行数限制、审计日志 |
| 行级权限 | 基于管理员配置的谓词注入 AST，不依赖模型输出；按用户/表/字段/允许值生效，字段名做标识符与语义层双重校验 |
| 结果呈现 | 表格、ECharts 图表、自然语言结论、多轮追问、口径澄清、SQL 与安全链路溯源 |
| 评测 | 120 题规则基线评测集（管理员专用），金标 SQL 对比、分类统计、异步任务，记录模型/prompt/数据集/commit |
| 可观测 | Micrometer + Prometheus，记录状态、延迟、Token 与成本；查询审计与用户反馈 |
| MCP | 将“问数”封装为 `ask_data` 工具，stdio JSON-RPC，可直接接入 Claude Desktop / Dify |

演示账号与数据权限：

| 用户 | 角色 | 数据范围 |
|---|---|---|
| `lisi` | 平台管理员 | 全部区域 |
| `zhangsan` | 华东销售经理 | `department_id = 1`（华东） |
| `wangwu` | 华南销售经理 | `department_id = 2`（华南） |

> 身份通过 `X-ChatBI-User` 请求头传递，仅用于演示，且该请求头可被任意客户端伪造。生产环境应替换为 OAuth2 / OIDC / 企业 SSO，并由网关注入可信身份。

---

## 2. 架构

```
┌──────────────────────────── Vue 3 + ECharts ────────────────────────────┐
│  问数聊天  语义层查看  评测中心  审计看板        亮/暗主题 · 多轮追问 · 图表溯源  │
└───────────────────────────────┬─────────────────────────────────────────┘
                                │ /api（nginx / Vite 代理）
┌───────────────────────────────▼─────────────────────────────────────────┐
│ Spring Boot 3 · JDK 21 虚拟线程                                          │
│                                                                          │
│  ChatController                                                          │
│    ├─ ConversationService      多轮上下文 / 追问继承                        │
│    ├─ SchemaLinkingService     同义词 + BM25 + pgvector + Few-shot          │
│    ├─ Nl2SqlAgent              Spring AI 生成 / Demo 离线规划器              │
│    ├─ SqlGuardService          AST 校验 + 行级权限注入                       │
│    ├─ QueryExecutor            EXPLAIN 代价 → 只读连接执行 → 行数/超时限制   │
│    ├─ ResultPresenter          表格 + 图表 + 结论 + 追问                     │
│    ├─ AuditService / Metrics   审计、Token、延迟、成本                      │
│    └─ EvaluationService        120 题异步评测                               │
└───────────────┬───────────────────────────────┬─────────────────────────┘
                │                               │
      ┌─────────▼─────────┐           ┌─────────▼─────────┐
      │ PostgreSQL 16     │           │ Redis 7.4         │
      │ + pgvector        │           │ Schema Linking 缓存│
      │ 业务视图 + 元数据   │           └───────────────────┘
      └───────────────────┘
```

关键设计：

- **两套数据源**：`dataSource` 用于元数据/审计/会话（Druid），`queryDataSource` 使用只读账号 `chatbi_ro` 执行模型生成的 SQL。
- **语义层是核心资产**：模型只能看到裁剪后的表、字段、指标、同义词与枚举；物理表对模型不可见。
- **权限独立于模型**：行级谓词在 AST 上注入，模型输出无法绕过。
- **离线可演示**：`CHATBI_AI_ENABLED=false` 时使用确定性的 `DemoSqlGenerator`，无需 API Key 即可完整演示；开启后走 OpenAI 兼容接口。AI 已启用但生成失败时默认返回 `ERROR`，**不会静默回退到规则引擎**；只有显式设置 `CHATBI_AI_ALLOW_DEMO_FALLBACK=true` 才允许降级，并在响应中标记 `degraded=true` 与原因。

---

## 3. 快速开始

### 3.1 Docker Compose（推荐）

```bash
cp .env.example .env        # 可选：填写 OPENAI_API_KEY 等
docker compose up -d --build
```

启动后：

| 服务 | 地址 |
|---|---|
| 前端 | http://localhost:8088 |
| 后端 API | http://localhost:8080 |
| Swagger UI | http://localhost:8080/swagger-ui.html |
| 健康检查 | http://localhost:8080/actuator/health |
| Prometheus | http://localhost:8080/actuator/prometheus |

首次启动会自动执行 Flyway 迁移、创建语义层视图并灌入演示数据。

### 3.2 本地开发

要求：JDK 21、Maven Wrapper、Node.js 20+、Docker。

```bash
# 1) 仅启动基础设施
docker compose up -d postgres redis

# 2) 后端（默认 8080）
./mvnw -pl backend spring-boot:run
# Windows: mvnw.cmd -pl backend spring-boot:run

# 3) 前端（默认 5174，/api 代理到 8080）
cd frontend
npm install
npm run dev
```

打开 http://localhost:5174。

### 3.3 运行测试与构建

```bash
./mvnw -pl backend test                 # 81 个单元测试
./mvnw -o clean package -DskipTests     # 后端 + MCP Server
cd frontend && npm run build            # vue-tsc + Vite 生产构建
```

以上命令与前端构建同样在 `.github/workflows/ci.yml` 中执行（JDK 21 + Node 20，含 Maven/npm 缓存）。

---

## 4. 使用方式

### 4.1 示例问题

- 今年各区域销售额
- 今年维修成本最高的 5 个设备
- 当前各仓库库存金额
- 今年销售额和订单数
- 今年华东区采购金额
- 今年应收账款账龄分布
- 那去年呢？ / 那订单数呢？（多轮追问）
- 帮我看看数据（触发口径澄清）

演示数据覆盖 6 个主题：销售、采购、库存、维修、应收、设备。

### 4.2 返回内容

每次问数返回：

- `status`：`OK` / `BLOCKED` / `ERROR` / `CLARIFY`
- `finalSql`、`explanation`（口径）、`answer`（自然语言结论）
- `table`（列/行/单位）与 `chart`（类型、维度、系列）
- `followUps`（推荐追问）、`clarification`（澄清选项）
- `conversationId`、`auditId`
- `schema` 命中、`guard` 安全链路、`rowScopes` 行级权限、`performance` 耗时

### 4.3 前端页面

- **问数**：聊天式交互，SQL / 安全链路可展开；图表支持亮暗主题。
- **语义层**：查看表、字段、指标、同义词、枚举、Few-shot 与行级权限；后端提供维护 API，**前端当前为只读查看，仅支持刷新索引**。
- **评测**：管理员可运行 120 题规则基线评测，查看总体准确率、分类准确率与逐题 SQL 对比；仅管理员可运行，结果记录用户、模型、prompt 版本、数据集 hash 与代码 commit。
- **审计**：查询明细、状态、耗时、Token、成本，管理员可切换全局视图。

---

## 5. 核心实现

### 5.1 语义层与 Schema Linking

语义资产存储于 `sem_table / sem_column / sem_metric / sem_synonym / sem_enum / few_shot_example / row_permission`，通过 `SemanticSnapshot` 以不可变快照形式提供读取，刷新时整体替换，避免读到半更新状态。

Schema Linking 流程：

1. **同义词扩展**：将“营业额 / 销售金额”等业务词映射到指标或字段。
2. **候选召回**：BM25（中文 uni/bi/tri-gram + 英文词）与向量召回并行；向量为 512 维确定性 Feature Hashing，存放在 pgvector。
3. **业务过滤**：枚举值（如“华东区”→`region='华东'`）参与匹配。
4. **Few-shot 检索**：按问题相似度选取示例 SQL。
5. **Schema 裁剪**：只把命中的表/字段/指标/枚举交给模型，降低幻觉与 Token 成本。

`HashingEmbeddingModel` 是离线确定性 Feature Hashing 实现，**不是语义 embedding**：它只能匹配字面相近的 token，语义泛化主要由同义词表和 BM25 承担。生产环境可换成 Spring AI `EmbeddingModel`（如 bge-m3 / text-embedding-3-large），但维度变化需要新增迁移修改 `vector(512)` 列、重建 HNSW 索引并全量重算，不能直接“无感替换”。

### 5.2 NL2SQL Agent

```
问题 + 多轮上下文 + 裁剪后的 Schema
        │
        ▼
   生成 SQL（Spring AI 或 Demo 规划器）
        │
        ▼
   SqlGuardService：语法/AST 白名单/权限
        │
        ▼
   QueryExecutor：EXPLAIN 代价 → 只读执行
        │
   ┌────┴────┐
  成功       失败（语法/字段/类型/超时）
   │          │
   ▼          ▼
 呈现    报错回灌，自修复（最多 2 次）
```

- 生成结果必须是单条 `SELECT`，禁止 CTE/子查询/UNION/INTERSECT/EXCEPT。
- 结构违规（CTE/子查询/UNION、未知表/字段、语法错误）进入有界修复循环，并附带明确的结构纠错提示（改写为单层 SELECT + GROUP BY/HAVING）。
- 写操作、危险函数、系统 schema、多语句、`SELECT INTO`、锁定读、行级权限拒绝属于硬策略，直接返回 `BLOCKED`，不交给模型重试。
- AI 已启用但生成失败时默认返回 `ERROR`，不静默切换规则引擎；只有 `CHATBI_AI_ALLOW_DEMO_FALLBACK=true` 才允许降级，响应会带 `degraded=true` 与原因。
- 修复提示包含数据库原始错误、当前 Schema 与上一次 SQL；达到重试上限后返回 `ERROR`，并保留失败阶段与信息。
- 多轮对话会继承上一轮的时间口径、过滤条件与主题；“那去年呢”只改时间，“那订单数呢”保留其余条件。

### 5.3 SQL 安全链路

| 检查 | 行为 |
|---|---|
| 空 SQL / 超长 | 拒绝 |
| 多语句 / 子查询 / CTE / UNION / INTERSECT / EXCEPT | 拒绝，只允许单层、单分支 SELECT |
| DDL / DML / 管理语句 | 关键字 + AST 双重拒绝 |
| 系统 schema | 拒绝 `information_schema`、`pg_catalog`、`pg_toast` |
| 危险函数 | 拒绝 `pg_sleep`、`pg_read_file`、`dblink`、`COPY` 等 |
| 表白名单 | 仅允许语义层注册的视图 |
| 行级权限 | 按管理员规则向 AST 注入 `AND` 谓词；列名必须是合法标识符且存在于语义层，否则拒绝；无匹配规则则拒绝 |
| EXPLAIN 代价 | 超过阈值拒绝执行 |
| 执行超时 / 行数 | 8s 超时、最多 500 行 |
| 数据库账号 | `chatbi_ro` 只读角色，仅授予 6 个 `v_*` 视图，元数据/审计/物理表不可读 |
| 审计 | 每次问数记录 SQL、状态、阶段、耗时、Token、成本、Schema 命中；审计写入失败时 fail-closed，不返回查询结果 |

被拦截的请求同样写入审计，并在前端展示失败阶段与原因。

---

## 6. 评测

评测集位于 `backend/src/main/resources/eval/questions.json`，共 **120 题**：

| 分类 | 数量 |
|---|---|
| 销售 | 35 |
| 维修 | 20 |
| 采购 / 库存 / 应收 | 各 15 |
| 设备 | 10 |
| 澄清 | 5 |
| 多指标 | 5 |

评测方法（执行准确率，Execution Accuracy）：

1. 金标 SQL 由只读账号执行一次，得到基线结果集。
2. 被测链路只跑一遍：直接复用问数 Agent 返回的 `result` 结果集，**不再二次执行生成的 SQL**，从而不会绕过 EXPLAIN 代价门禁、审计与行级权限。
3. 校验状态（澄清题必须返回 `CLARIFY`）、行数与列数。
4. 按值集合做列匹配，行顺序不敏感。
5. 数值统一规范化后比较（展示保留 2 位、比较保留 6 位），避免精度噪声。
6. 按分类汇总准确率，异步任务轮询进度。

评测入口仅限管理员（`POST /api/eval/run` 在 Controller 与 Service 两层都校验 `requireAdmin`）。原因是金标 SQL 不注入行级权限、返回全量数据，如果允许普通用户运行，就会成为绕过行级权限读取全量数据的旁路。每个任务记录运行用户、模型名、Prompt 版本号（`PromptBuilder.VERSION`）、评测集版本与 SHA-256、代码 commit（`GIT_COMMIT` / `GITHUB_SHA`），便于复现与横向对比。

运行方式：

```bash
# 管理员身份
curl -X POST http://localhost:8080/api/eval/run \
  -H 'Content-Type: application/json' \
  -H 'X-ChatBI-User: lisi' \
  -d '{"category":"sales","limit":10}'
```

也可以在前端「评测」页运行全量评测。

### 6.1 两种基线

评测集相同、判定规则相同，但规划器不同：

| 基线 | 规划器 | 结果 | 说明 |
|---|---|---|---|
| 规则基线 | `DemoSqlGenerator`（`CHATBI_AI_ENABLED=false`） | **120/120（100%）** | 只验证语义层、执行链路、权限与评测框架；题目与规则同源，不代表开放问题能力 |
| 真实 LLM 基线 | `deepseek-flash`（OpenAI 兼容网关） | **118/120（98.33%）** | 下表为 2026-10-23 的真实运行结果 |

### 6.2 真实 LLM 基线（2026-10-23）

| 项目 | 值 |
|---|---|
| 模型 / 温度 | `deepseek-flash` / `0.1` |
| Prompt 版本 | `2026-10-23.2` |
| 评测集 | `1.0`，SHA-256 `fb19ec87705ed85d86096a89dbe04f9364bdb8533115ac8d86c545079d55d62a` |
| 代码 commit | `b28dda1183554f64b9dfb14b4d3e46ac756b5058` |
| 时间 | 2026-10-23 15:38:49 → 15:47:17（Asia/Shanghai） |
| 总体 | **118/120（98.33%）** |
| LLM 调用 | 120 次，失败 0，重试 0，JSON-mode 回退 0 |
| Token | 输入 224,550；输出 16,533；合计 241,083 |
| 成本 | 网关未公布单价，`cost_usd=0`；配置单价后按 `usage` 自动计算 |
| 端到端延迟 | 平均 4,229ms，P50 3,469ms，P95 8,890ms，最大 25,585ms |
| LLM 请求延迟 | 合计 484.8s，最大 4.24s（其余为 Schema Linking / 安全校验 / EXPLAIN / 执行） |

分类准确率：

| 分类 | 题数 | 通过 | 准确率 |
|---|---|---|---|
| 销售 | 35 | 34 | 97.14% |
| 采购 | 15 | 15 | 100% |
| 库存 | 15 | 15 | 100% |
| 维修 | 20 | 20 | 100% |
| 应收 | 15 | 14 | 93.33% |
| 设备 | 10 | 10 | 100% |
| 澄清 | 5 | 5 | 100% |
| 多指标 | 5 | 5 | 100% |

失败题：

| # | 分类 / 难度 | 问题 | 失败原因 |
|---|---|---|---|
| 28 | 销售 / hard | 华东和华南今年销售额 | 模型按区域分组返回 2 行，金标为 1 行合计；属于“是否分组”的口径歧义 |
| 88 | 应收 / easy | 当前开票金额 | 模型返回口径澄清，金标直接对 `v_receivable_fact` 求和 |

> 复现命令见 `ao` artifact `newterm-8/chatbi-llm-production-baseline.md`；规则基线与 LLM 基线使用同一评测集与同一执行准确率判定（行序不敏感、数值容差、状态校验）。

---

## 7. 可观测性与审计

Prometheus 指标前缀为 `chatbi`：

- `chatbi_queries_total{status,mode}`：请求量
- `chatbi_query_duration_seconds{status}`：端到端延迟
- `chatbi_tokens{kind}`：Prompt / Completion Token
- `chatbi_cost_usd{mode}`：按配置单价估算的成本（未配置单价时为 0）
- `chatbi_llm_calls_total{model,outcome}`：LLM 调用成功/失败
- `chatbi_llm_request_duration_seconds{model,outcome}`：LLM 单次请求延迟
- `chatbi_llm_tokens_total{model,kind}`：LLM Prompt / Completion Token
- `chatbi_sql_blocked_total{code}`：安全拦截分类
- `chatbi_clarifications_total`：澄清次数

AI 诊断：

- `GET /actuator/health` 的 `ai` 组件：`mode`、`model`、`baseUrl`、`clientAvailable`、`jsonModeSupported`、调用/失败/重试次数、最近一次错误与 Token。
- `GET /api/health/ai`：同一份快照，便于脚本与告警查询；两者都不包含 API Key。

审计表 `query_audit` 保存每次请求的问题、生成 SQL、最终 SQL、状态、失败阶段、尝试次数、行数、耗时、Token、成本与 Schema 命中；用户可对查询提交反馈（`POST /api/feedback`）。

接入 Prometheus 后可直接配置延迟 P95、错误率、Token 成本与拦截率告警。

---

## 8. MCP Server

`mcp-server` 将问数封装为单个工具 `ask_data`，通过 stdio 暴露 JSON-RPC（协议 `2024-11-05`）。

构建：

```bash
./mvnw -pl mcp-server package -DskipTests
# 产物：mcp-server/target/chatbi-mcp-server-jar-with-dependencies.jar
```

环境变量：

| 变量 | 默认值 | 说明 |
|---|---|---|
| `CHATBI_API_URL` | `http://localhost:8080` | 后端地址 |
| `CHATBI_MCP_USER` | `zhangsan` | 默认演示用户 |

### 8.1 Claude Desktop

编辑 `claude_desktop_config.json`：

```json
{
  "mcpServers": {
    "chatbi": {
      "command": "java",
      "args": [
        "-jar",
        "D:\\pl0\\newterm\\mcp-server\\target\\chatbi-mcp-server-jar-with-dependencies.jar"
      ],
      "env": {
        "CHATBI_API_URL": "http://localhost:8080",
        "CHATBI_MCP_USER": "lisi"
      }
    }
  }
}
```

重启 Claude Desktop 后，可直接让 Claude 调用 `ask_data`，例如：“用 ChatBI 查今年各区域销售额”。

### 8.2 Dify

Dify 通过 SSE / HTTP 接入 MCP，可用 `supergateway` 将 stdio 桥接为 SSE：

```bash
npx -y supergateway \
  --stdio "java -jar D:/pl0/newterm/mcp-server/target/chatbi-mcp-server-jar-with-dependencies.jar" \
  --port 8000
```

在 Dify「工具 → MCP」中填写 `http://host.docker.internal:8000/sse`（Dify 在 Docker 中时）或 `http://localhost:8000/sse`。工具参数：

- `question`（必填）：自然语言问题
- `user`（可选）：演示用户
- `conversationId`（可选）：多轮追问时传入上次返回值

---

## 9. 配置

主要环境变量（完整见 `.env.example` 与 `backend/src/main/resources/application.yml`）：

| 变量 | 默认值 | 说明 |
|---|---|---|
| `CHATBI_AI_ENABLED` | `false` | 是否启用真实大模型 |
| `CHATBI_AI_ALLOW_DEMO_FALLBACK` | `false` | AI 启用但生成失败时是否允许降级到规则引擎；开启后响应标记 `degraded=true` |
| `CHATBI_AI_API_KEY` | 空 | OpenAI 兼容 API Key（`OPENAI_API_KEY` 仍兼容） |
| `CHATBI_AI_BASE_URL` | `https://api.openai.com` | 兼容网关地址 |
| `CHATBI_AI_MODEL` | `gpt-4.1-mini` | 模型名称 |
| `CHATBI_AI_TEMPERATURE` | `0.1` | 采样温度 |
| `CHATBI_AI_MAX_TOKENS` | `1400` | 单次输出上限（含推理模型的 reasoning token） |
| `CHATBI_AI_JSON_MODE` | `true` | 请求 `response_format=json_object`；网关拒绝时自动回退纯文本解析 |
| `CHATBI_AI_TIMEOUT_SECONDS` | `45` | 读超时 |
| `CHATBI_AI_CONNECT_TIMEOUT_SECONDS` | `10` | 连接超时 |
| `CHATBI_AI_MAX_RETRIES` | `2` | 429/5xx/超时的最大重试次数（指数退避） |
| `CHATBI_AI_RETRY_BACKOFF_MS` | `800` | 首次重试退避基数 |
| `CHATBI_AI_MAX_PROMPT_CHARS` | `28000` | system + user 总字符预算（估算，非计费） |
| `CHATBI_AI_MAX_SCHEMA_CHARS` | `16000` | Schema/Few-shot 段预算，超限时优先丢弃示例 |
| `CHATBI_AI_MAX_HISTORY_CHARS` | `2000` | 历史对话预算，超限按最旧优先丢弃 |
| `CHATBI_AI_MAX_HISTORY_TURNS` | `3` | 最多携带的历史轮数 |
| `CHATBI_AI_MAX_QUESTION_CHARS` | `2000` | 单条问题清洗后的上限 |
| `CHATBI_AI_INPUT_COST_PER_MILLION` | `0` | 输入单价（每 1M tokens）；未知时保持 0，不写死不可信价格 |
| `CHATBI_AI_OUTPUT_COST_PER_MILLION` | `0` | 输出单价（每 1M tokens） |
| `CHATBI_EMBEDDING_PROVIDER` | `hash` | 仅实现 `hash`；远程 embedding 需迁移与重建索引 |
| `CHATBI_EMBEDDING_DIMENSIONS` | `512` | 必须与 `semantic_embedding.embedding` 的 `vector(512)` 一致 |
| `CHATBI_READONLY_DB_USER` | `chatbi_ro` | 只读执行账号 |
| `CHATBI_READONLY_DB_PASSWORD` | `chatbi_ro` | 只读账号密码 |
| `CHATBI_ALLOWED_ORIGINS` | `http://localhost:5174,http://localhost:8088` | CORS 白名单 |
| `CHATBI_QUERY_MAX_ROWS` | `500` | 最大返回行数 |
| `CHATBI_QUERY_TIMEOUT_SECONDS` | `8` | 查询超时 |

> `CHATBI_AI_ENABLED=true` 但没有可用的 API Key/客户端时，应用会在启动阶段失败，而不是静默使用规则引擎。启动日志会输出 `AI mode=LLM model=... baseUrl=...`（不含 Key），运行时可查询 `GET /api/health/ai` 或 `/actuator/health` 的 `ai` 组件。

> 生产环境务必修改数据库密码、关闭演示身份头、限制网络暴露，并为只读账号配置最小权限。

---

## 10. 项目结构

```
.
├── backend/
│   ├── src/main/java/com/chatbi/
│   │   ├── linking/        # 分词、BM25、哈希向量、Schema Linking
│   │   ├── semantic/       # 语义快照与元数据服务
│   │   ├── nl2sql/         # Agent、提示词、Demo 规划器、自修复
│   │   ├── sql/            # AST 校验、行级权限注入、执行与代价
│   │   ├── service/        # 会话、审计、当前用户
│   │   ├── eval/           # 评测服务
│   │   ├── metrics/        # Micrometer 指标
│   │   └── web/            # REST Controllers
│   └── src/main/resources/
│       ├── db/migration/   # Flyway：语义层、视图、种子数据
│       └── eval/           # 120 题评测集
├── frontend/
│   ├── src/components/     # Chat / Chart / Table / Semantic / Eval / Audit
│   ├── Dockerfile          # Node 构建 + nginx
│   └── nginx.conf          # /api 反向代理
├── mcp-server/             # stdio MCP Server
├── infra/postgres/init/    # 只读角色初始化
└── docker-compose.yml      # postgres / redis / backend / frontend
```

---

## 11. 已知边界

- 默认使用确定性 Demo 规划器，适合离线演示与回归；它是**规则基线**，不代表大模型能力，真实模型效果需接入后重测。
- AI 启用后生成失败默认返回 `ERROR`，不会静默降级；如需兜底须显式开启 `CHATBI_AI_ALLOW_DEMO_FALLBACK`，并在响应中标记 `degraded`。
- SQL 安全策略只允许单层、单分支 SELECT，禁止 CTE / 子查询 / UNION / INTERSECT / EXCEPT；复杂分析需改写为 `GROUP BY` / `HAVING` 聚合，或按需扩展策略（如引入受控 CTE）。
- 语义层由后端维护 API 管理，前端目前只有只读查看与刷新索引，没有可视化编辑界面。
- 向量检索使用离线哈希嵌入，不具备真实语义泛化能力；替换为托管 Embedding 后效果更好，但需迁移 `vector(512)` 列并重建索引。
- 演示身份基于可伪造的请求头，未实现登录、会话鉴权与租户隔离。
- 评测仅限管理员运行，且是规则基线口径；评测集与演示数据为合成数据，指标口径用于展示方法而非业务结论。

---

## 12. 参考

- Spring AI：https://docs.spring.io/spring-ai/reference/
- pgvector：https://github.com/pgvector/pgvector
- JSqlParser：https://github.com/JSQLParser/JSqlParser
- MCP：https://modelcontextprotocol.io/
- supergateway：https://github.com/supercorp-ai/supergateway
