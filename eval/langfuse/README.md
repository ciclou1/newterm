# ChatBI → Langfuse 评测接入

把 ChatBI 的 120 题 execution-accuracy 评测结果镜像到 Langfuse：每题一条
trace（根 span + `nl2sql` generation），关联到 dataset item 组成一次
dataset run，并写入 `execution_accuracy` score。

## 前置条件

- Python **3.13**
- 可访问的 ChatBI 后端（管理员账号）
- 可访问的 Langfuse（本项目自托管版本为 **4.70.1**，默认 `http://localhost:3002`）

启动本机 Langfuse（数据卷保留；Docker Desktop 需先运行）：

```bash
docker compose -p langfuse-selfhost \
  --env-file "$HOME/.langfuse-selfhost.env" up -d --wait
```

## 凭据与环境变量

凭据只从环境变量读取，**不要**提交 `.env`、不要打印到日志或报告。

| 变量 | 必填 | 说明 |
|---|---|---|
| `CHATBI_BASE_URL` | 否 | 默认 `http://localhost:8080`；可用 `--chatbi-url` 覆盖 |
| `CHATBI_USERNAME` | 是 | 管理员用户名（评测接口仅管理员可调用） |
| `CHATBI_PASSWORD` | 是 | 管理员口令 |
| `LANGFUSE_HOST` | 否 | 默认 `http://localhost:3002`；可用 `--langfuse-host` 覆盖 |
| `LANGFUSE_PUBLIC_KEY` | 是 | Langfuse project public key（`pk-lf-…`） |
| `LANGFUSE_SECRET_KEY` | 是 | Langfuse project secret key（`sk-lf-…`） |
| `CHATBI_EVAL_DATASET` | 否 | 默认 `chatbi-eval-120-v1`；可用 `--dataset` 覆盖 |

仓库外的 env 文件示例（只在本机 source，不进入版本库）：

```bash
set -a
. "$HOME/.langfuse-selfhost.env"    # LANGFUSE_INIT_PROJECT_PUBLIC_KEY / SECRET_KEY
# 按实际变量名导出；示例中把 INIT 变量映射为运行时变量：
export LANGFUSE_PUBLIC_KEY="$LANGFUSE_INIT_PROJECT_PUBLIC_KEY"
export LANGFUSE_SECRET_KEY="$LANGFUSE_INIT_PROJECT_SECRET_KEY"
export CHATBI_USERNAME="lisi"
export CHATBI_PASSWORD="<从部署环境读取，不写入文件>"
export CHATBI_BASE_URL="http://localhost:18082"
set +a
```

## 安装与运行

```bash
cd eval/langfuse
python -m venv .venv
# Windows: .venv\Scripts\activate
. .venv/bin/activate
pip install -r requirements.txt
```

全量 120 题：

```bash
python run_langfuse_eval.py --output last-run.json
```

常用参数：

| 参数 | 作用 |
|---|---|
| `--limit 1` | 只跑前 1 题（冒烟；建议同时给一个独立的 `--run-name`） |
| `--category sales` | 只跑某一分类 |
| `--run-name chatbi-eval-<jobId>` | 指定 Langfuse run 名；重跑请换新名称，避免 run item 重复 |
| `--questions path/to/questions.json` | 默认读取 `backend/src/main/resources/eval/questions.json` |
| `--output path.json` | 运行摘要输出路径；默认 `last-run.json`（已 gitignore） |
| `--skip-eval --job-json job.json` | 复用已保存的 `EvalJobView`，只补写 Langfuse |

脚本流程：

1. 校验 Langfuse 健康状态，登录 ChatBI。
2. `POST /api/eval/run`，轮询 `GET /api/eval/jobs/{id}` 到 `COMPLETED`。
3. 创建/复用 dataset `chatbi-eval-120-v1`，按 `chatbi-q-<id>` 幂等同步 120 个
   item：`input=question`，`expectedOutput={goldenSql, expectedStatus}`，
   `metadata={id, category, difficulty, tables}`。
4. 每条 `CaseResult` 写一条 trace；generation 带 model、token usage 与 cost。
5. 通过 `POST /api/public/dataset-run-items` 把 trace 关联到 dataset item。
6. 写入 `execution_accuracy`（1/0）score，并按 API 回查 dataset item 数、run
   item 数、score 数与平均分，和 ChatBI job 的 accuracy 对比。
7. 打印摘要并写入 `--output`。

## Langfuse UI 查看路径

- Dataset：`Datasets → chatbi-eval-120-v1`
- Dataset run：dataset 页面内 run 名 `chatbi-eval-<jobId>`（Traces / Items 视图）
- Trace 详情：run item 中的 trace，可看到根 span 与 `nl2sql` generation 的
  model、usage、cost、metadata
- Scores：trace/run 详情中的 `execution_accuracy`，或 `Scores` 页面按名称筛选

## SDK 与 REST 的边界

- dataset、dataset item、trace/generation、score 使用 `langfuse` Python SDK
  （固定版本见 `requirements.txt`）。
- SDK 5.0.4 **没有** dataset-run-item 方法，因此 run item 关联使用公开 REST：
  `POST /api/public/dataset-run-items`（body：`runName`、`datasetItemId`、
  `traceId`、`createdAt`）。该端点在 Langfuse 4.70.1 的 OpenAPI/源码中存在；
  本机 `/api/public/openapi.json` 返回 404，可参考 Langfuse 源码
  `web/public/generated/api/openapi.yml` 或
  `web/src/pages/api/public/dataset-run-items.ts`。
- 校验 score 使用 `GET /api/public/v3/scores?traceId=…`（按 traceId 每 20 条分批）；
  score 只挂 trace（Langfuse 要求 traceId / sessionId / datasetRunId 三选一），与
  dataset run 的关联由 run item 建立。

## 数据隐私

- 上传到 Langfuse 的字段仅限：问题文本、预期状态/golden SQL、生成 SQL、状态、
  错误/比较消息、行数（`expectedRows`/`actualRows`，**不是行数据**）、
  latency、model、token 数与 cost、评测元数据（job id、prompt/dataset 版本与
  hash、commit、分类、难度）。
- 不读取、不上传数据库行内容、API Key、JWT、用户口令或 Langfuse 密钥。
- 脚本打印中不输出凭据；请勿把含凭据的 `.env`、终端历史或截图提交到仓库。
- 截图仅包含 Langfuse UI 的合成评测数据。
