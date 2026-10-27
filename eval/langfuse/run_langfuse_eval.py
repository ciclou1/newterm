#!/usr/bin/env python3
"""Run the ChatBI 120-question evaluation and mirror every case into Langfuse.

The script is deliberately split into three stages so a failure is attributable:

1. Sync ``backend/src/main/resources/eval/questions.json`` into a Langfuse
   dataset (``chatbi-eval-120-v1`` by default).
2. Ask a running ChatBI backend to execute ``POST /api/eval/run`` and poll the
   in-memory job until it reaches ``COMPLETED``/``FAILED``.
3. Write one Langfuse trace per ``CaseResult`` (root span + generation with
   model/usage/cost), link the trace to its dataset item, and score it with
   ``execution_accuracy``.

Langfuse SDK 5.0.4 exposes dataset, dataset-item and score creation but no
dataset-run-item method; the run item is therefore created through the public
REST endpoint ``POST /api/public/dataset-run-items`` (confirmed in the Langfuse
OpenAPI/source for the deployed 4.70.1 server). Everything else uses the SDK.

Credentials are read from the environment only. Nothing is written to the
repository except the optional run summary (``last-run.json`` is gitignored).
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable

import httpx
from langfuse import Langfuse

REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_QUESTIONS = REPO_ROOT / "backend/src/main/resources/eval/questions.json"
DEFAULT_DATASET = "chatbi-eval-120-v1"
DEFAULT_OUTPUT = Path(__file__).resolve().parent / "last-run.json"
SCORE_NAME = "execution_accuracy"


def log(message: str) -> None:
    print(message, flush=True)


def required_env(name: str) -> str:
    value = os.environ.get(name, "").strip()
    if not value:
        raise SystemExit(f"缺少环境变量 {name}（从仓库外的 env 文件注入，不要写入仓库）")
    return value


def iso_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def sha256_file(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def load_questions(path: Path) -> list[dict[str, Any]]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    questions = payload.get("questions")
    if not isinstance(questions, list) or not questions:
        raise SystemExit(f"评测集没有 questions: {path}")
    ids = [int(q["id"]) for q in questions]
    if len(ids) != len(set(ids)):
        raise SystemExit("评测集存在重复 id")
    return questions


class ChatBiClient:
    def __init__(self, base_url: str, username: str, password: str) -> None:
        self.base_url = base_url.rstrip("/")
        self.username = username
        self._password = password
        self._client = httpx.Client(base_url=self.base_url, timeout=60.0)
        self._token: str | None = None

    def close(self) -> None:
        self._client.close()

    def login(self) -> None:
        response = self._client.post(
            "/api/auth/login",
            json={"username": self.username, "password": self._password},
        )
        if response.status_code != 200:
            raise SystemExit(f"ChatBI 登录失败: HTTP {response.status_code} {response.text[:300]}")
        token = response.json().get("accessToken")
        if not token:
            raise SystemExit("ChatBI 登录响应缺少 accessToken")
        self._token = token
        log(f"ChatBI 登录成功: {self.base_url} user={self.username}")

    def _headers(self) -> dict[str, str]:
        if self._token is None:
            self.login()
        return {"Authorization": f"Bearer {self._token}"}

    def start_eval(self, category: str | None, limit: int | None) -> dict[str, Any]:
        body: dict[str, Any] = {}
        if category:
            body["category"] = category
        if limit:
            body["limit"] = limit
        response = self._client.post("/api/eval/run", json=body, headers=self._headers())
        if response.status_code not in (200, 201):
            raise SystemExit(
                f"启动评测失败: HTTP {response.status_code} {response.text[:500]}"
            )
        job = response.json()
        log(f"评测任务已启动: {job['id']} total={job['total']} model={job.get('model')}")
        return job

    def poll_job(self, job_id: str, interval_s: float, timeout_s: float) -> dict[str, Any]:
        deadline = time.time() + timeout_s
        last_completed = -1
        while True:
            response = self._client.get(f"/api/eval/jobs/{job_id}", headers=self._headers())
            if response.status_code != 200:
                raise SystemExit(
                    f"查询评测任务失败: HTTP {response.status_code} {response.text[:300]}"
                )
            job = response.json()
            completed = int(job.get("completed") or 0)
            total = int(job.get("total") or 0)
            if completed != last_completed:
                log(f"  评测进度 {completed}/{total} passed={job.get('passed')}")
                last_completed = completed
            status = job.get("status")
            if status in ("COMPLETED", "FAILED"):
                if status == "FAILED":
                    raise SystemExit(f"评测任务失败: {job.get('error')}")
                log(
                    f"评测完成: {job.get('passed')}/{total} "
                    f"accuracy={job.get('accuracy')}% dataset={job.get('datasetVersion')} "
                    f"hash={str(job.get('datasetHash'))[:12]}…"
                )
                return job
            if time.time() >= deadline:
                raise SystemExit(f"评测任务在 {timeout_s:.0f}s 内未完成（最后进度 {completed}/{total}）")
            time.sleep(interval_s)


class LangfuseRest:
    """Small REST client for endpoints the Python SDK does not expose."""

    def __init__(self, host: str, public_key: str, secret_key: str) -> None:
        self.host = host.rstrip("/")
        self._client = httpx.Client(
            base_url=self.host,
            auth=(public_key, secret_key),
            timeout=60.0,
        )

    def close(self) -> None:
        self._client.close()

    def get(self, path: str, **params: Any) -> dict[str, Any]:
        response = self._client.get(path, params=params)
        if response.status_code != 200:
            raise SystemExit(
                f"Langfuse GET {path} 失败: HTTP {response.status_code} {response.text[:500]}"
            )
        return response.json()

    def post(self, path: str, payload: dict[str, Any]) -> dict[str, Any]:
        response = self._client.post(path, json=payload)
        if response.status_code not in (200, 201, 202):
            raise SystemExit(
                f"Langfuse POST {path} 失败: HTTP {response.status_code} {response.text[:500]}"
            )
        return response.json() if response.content else {}

    def paginate(self, path: str, key: str = "data", limit: int = 100, **params: Any) -> list[dict[str, Any]]:
        rows: list[dict[str, Any]] = []
        page = 1
        while True:
            payload = self.get(path, **params, limit=limit, page=page)
            batch = payload.get(key) or []
            rows.extend(batch)
            meta = payload.get("meta") or {}
            total_pages = int(meta.get("totalPages") or 0)
            if not batch or page >= total_pages:
                break
            page += 1
        return rows

    def dataset(self, name: str) -> dict[str, Any]:
        return self.get(f"/api/public/datasets/{name}")

    def dataset_item_count(self, name: str) -> int:
        payload = self.get("/api/public/dataset-items", datasetName=name, limit=1, page=1)
        return int((payload.get("meta") or {}).get("totalItems") or 0)

    def run_item_count(self, dataset_id: str, run_name: str) -> int:
        payload = self.get(
            "/api/public/dataset-run-items",
            datasetId=dataset_id,
            runName=run_name,
            limit=1,
            page=1,
        )
        return int((payload.get("meta") or {}).get("totalItems") or 0)

    def trace(self, trace_id: str) -> dict[str, Any] | None:
        response = self._client.get(f"/api/public/traces/{trace_id}")
        if response.status_code == 404:
            return None
        if response.status_code != 200:
            raise SystemExit(
                f"Langfuse 查询 trace 失败: HTTP {response.status_code} {response.text[:300]}"
            )
        return response.json()

    def create_run_item(
        self,
        run_name: str,
        dataset_item_id: str,
        trace_id: str,
        run_description: str,
        metadata: dict[str, Any],
    ) -> dict[str, Any]:
        return self.post(
            "/api/public/dataset-run-items",
            {
                "runName": run_name,
                "runDescription": run_description,
                "metadata": metadata,
                "datasetItemId": dataset_item_id,
                "traceId": trace_id,
                "createdAt": iso_now(),
            },
        )

    def scores_for_traces(self, trace_ids: Iterable[str], name: str) -> list[dict[str, Any]]:
        """Trace-level scores; the dataset-run link is carried by the run items."""
        rows: list[dict[str, Any]] = []
        ids = list(trace_ids)
        for start in range(0, len(ids), 20):
            chunk = ids[start:start + 20]
            payload = self.get(
                "/api/public/v3/scores",
                limit=100,
                name=name,
                traceId=",".join(chunk),
                fields="details,subject",
            )
            rows.extend(payload.get("data") or [])
        return rows


def ensure_dataset(client: Langfuse, rest: LangfuseRest, name: str, description: str) -> dict[str, Any]:
    client.create_dataset(name=name, description=description, metadata={"source": "chatbi-eval"})
    dataset = rest.dataset(name)
    log(f"Langfuse dataset 就绪: {name} (id={dataset.get('id')})")
    return dataset


def sync_dataset_items(
    client: Langfuse,
    dataset_name: str,
    questions: list[dict[str, Any]],
    dataset_version: str,
) -> int:
    dataset_client = client.get_dataset(dataset_name)
    existing = {item.id: item for item in dataset_client.items}
    created = 0
    updated = 0
    for question in questions:
        item_id = f"chatbi-q-{int(question['id']):03d}"
        input_value = question["question"]
        expected = {
            "goldenSql": question.get("goldenSql"),
            "expectedStatus": question.get("expectedStatus"),
        }
        metadata = {
            "id": question["id"],
            "category": question.get("category"),
            "difficulty": question.get("difficulty"),
            "tables": question.get("tables") or [],
        }
        current = existing.get(item_id)
        if (
            current is not None
            and current.input == input_value
            and current.expected_output == expected
            and (current.metadata or {}) == metadata
        ):
            continue
        client.create_dataset_item(
            id=item_id,
            dataset_name=dataset_name,
            input=input_value,
            expected_output=expected,
            metadata=metadata,
        )
        if current is None:
            created += 1
        else:
            updated += 1
    log(
        f"dataset items 同步完成: 新增 {created}，更新 {updated}，"
        f"现有 {len(existing)}，目标 {len(questions)}（datasetVersion={dataset_version}）"
    )
    return len(existing) + created


def compact_rows(raw: Any) -> dict[str, Any]:
    """CaseResult carries row counts, not row payloads; keep the trace privacy-safe."""
    if raw is None:
        return {"expected": 0, "actual": 0}
    return {
        "expected": int(raw.get("expectedRows") or 0),
        "actual": int(raw.get("actualRows") or 0),
    }


def trace_metadata(case: dict[str, Any], job: dict[str, Any], dataset_hash: str) -> dict[str, Any]:
    code = case.get("code")
    if not code:
        code = "PASSED" if case.get("passed") else "FAILED"
    return {
        "evalJobId": job.get("id"),
        "caseId": case.get("id"),
        "category": case.get("category"),
        "difficulty": case.get("difficulty"),
        "code": code,
        "model": case.get("model") or job.get("model"),
        "promptVersion": job.get("promptVersion"),
        "datasetVersion": job.get("datasetVersion"),
        "datasetHash": dataset_hash,
        "commit": job.get("commit"),
        "latencyMs": case.get("latencyMs"),
        "mode": case.get("mode"),
        "passed": case.get("passed"),
    }


def write_trace(
    client: Langfuse,
    case: dict[str, Any],
    job: dict[str, Any],
    dataset_hash: str,
) -> tuple[str, dict[str, Any]]:
    trace_id = client.create_trace_id()
    metadata = trace_metadata(case, job, dataset_hash)
    prompt_tokens = int(case.get("promptTokens") or 0)
    completion_tokens = int(case.get("completionTokens") or 0)
    cost = case.get("costUsd")
    model = case.get("model") or job.get("model")
    output = {
        "status": case.get("status"),
        "generatedSql": case.get("generatedSql"),
        "message": case.get("message"),
        "rows": compact_rows(case),
    }
    generation_metadata = {
        **metadata,
        "promptTokens": prompt_tokens,
        "completionTokens": completion_tokens,
        "costUsd": cost,
    }
    with client.start_as_current_observation(
        trace_context={"trace_id": trace_id},
        name=f"chatbi-eval-q{case['id']}",
        as_type="span",
        input=case.get("question"),
        output=output,
        metadata=metadata,
    ):
        with client.start_as_current_observation(
            name="nl2sql",
            as_type="generation",
            model=model or "unknown",
            input={"question": case.get("question")},
            output={"status": case.get("status"), "generatedSql": case.get("generatedSql")},
            usage_details={
                "input": prompt_tokens,
                "output": completion_tokens,
                "total": prompt_tokens + completion_tokens,
            },
            cost_details={"total": float(cost) if cost is not None else 0.0},
            metadata=generation_metadata,
        ):
            pass
    return trace_id, metadata


def wait_for_traces(rest: LangfuseRest, trace_ids: list[str], timeout_s: float) -> int:
    pending = list(trace_ids)
    deadline = time.time() + timeout_s
    while pending:
        missing: list[str] = []
        for trace_id in pending:
            if rest.trace(trace_id) is None:
                missing.append(trace_id)
        if not missing:
            log(f"Langfuse trace 已全部可查询: {len(trace_ids)} 条")
            return len(trace_ids)
        pending = missing
        if time.time() >= deadline:
            raise SystemExit(f"仍有 {len(pending)} 条 trace 在 {timeout_s:.0f}s 内不可查询")
        log(f"  等待 trace 入库: 剩余 {len(pending)} 条")
        time.sleep(3)
    return len(trace_ids)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--chatbi-url", default=os.environ.get("CHATBI_BASE_URL", "http://localhost:8080"))
    parser.add_argument("--langfuse-host", default=os.environ.get("LANGFUSE_HOST", "http://localhost:3002"))
    parser.add_argument("--questions", type=Path, default=DEFAULT_QUESTIONS)
    parser.add_argument("--dataset", default=os.environ.get("CHATBI_EVAL_DATASET", DEFAULT_DATASET))
    parser.add_argument(
        "--run-name",
        default=None,
        help="Langfuse dataset run 名称；默认 chatbi-eval-<jobId>。重跑请使用新名称。",
    )
    parser.add_argument("--limit", type=int, default=None, help="仅执行前 N 题（用于冒烟验证）")
    parser.add_argument("--category", default=None, help="仅执行某一分类")
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT, help="运行摘要 JSON 路径")
    parser.add_argument("--poll-interval", type=float, default=5.0)
    parser.add_argument("--eval-timeout", type=float, default=1800.0)
    parser.add_argument("--trace-timeout", type=float, default=180.0)
    parser.add_argument("--skip-eval", action="store_true", help="复用 --job-json 中的结果，不重新跑评测")
    parser.add_argument("--job-json", type=Path, default=None, help="已保存的 EvalJobView JSON")
    args = parser.parse_args()

    questions = load_questions(args.questions)
    dataset_hash = sha256_file(args.questions)
    log(f"加载评测集: {args.questions} ({len(questions)} 题, sha256={dataset_hash[:12]}…)")

    chatbi_user = required_env("CHATBI_USERNAME")
    chatbi_password = required_env("CHATBI_PASSWORD")
    langfuse_public = required_env("LANGFUSE_PUBLIC_KEY")
    langfuse_secret = required_env("LANGFUSE_SECRET_KEY")

    chatbi = ChatBiClient(args.chatbi_url, chatbi_user, chatbi_password)
    rest = LangfuseRest(args.langfuse_host, langfuse_public, langfuse_secret)
    sdk = Langfuse(
        public_key=langfuse_public,
        secret_key=langfuse_secret,
        host=args.langfuse_host,
        environment="chatbi-eval",
    )
    try:
        health = rest.get("/api/public/health")
        log(f"Langfuse 可用: {args.langfuse_host} version={health.get('version')}")

        chatbi.login()
        if args.skip_eval:
            if not args.job_json:
                raise SystemExit("--skip-eval 需要同时提供 --job-json")
            job = json.loads(args.job_json.read_text(encoding="utf-8"))
            log(f"复用评测结果: {job['id']} {job.get('passed')}/{job.get('total')}")
        else:
            job = chatbi.start_eval(args.category, args.limit)
            job = chatbi.poll_job(job["id"], args.poll_interval, args.eval_timeout)

        results: list[dict[str, Any]] = list(job.get("results") or [])
        if not results:
            raise SystemExit("评测任务没有返回 CaseResult")
        if not (args.limit or args.category) and len(results) != len(questions):
            raise SystemExit(
                f"全量评测结果数量不一致: {len(results)} != {len(questions)}"
            )
        log(f"评测结果已获取: {len(results)} 条；检查 token 用量…")
        with_usage = sum(1 for item in results if int(item.get("promptTokens") or 0) > 0)
        if with_usage == 0:
            raise SystemExit("所有 CaseResult 的 promptTokens 均为 0，评测结果未记录 token 用量")
        log(f"  {with_usage}/{len(results)} 条结果带 promptTokens（model={results[0].get('model')}）")

        dataset = ensure_dataset(
            sdk,
            rest,
            args.dataset,
            "ChatBI 120 题 execution-accuracy 评测集（问题 → golden SQL/expected status）",
        )
        sync_dataset_items(sdk, args.dataset, questions, str(job.get("datasetVersion")))
        dataset_item_count = rest.dataset_item_count(args.dataset)
        if dataset_item_count != len(questions):
            raise SystemExit(
                f"dataset item 数量不一致: {dataset_item_count} != {len(questions)}"
            )

        run_name = args.run_name or f"chatbi-eval-{job['id']}"
        run_description = f"ChatBI evaluation job {job['id']} ({job.get('model')})"
        run_metadata = {
            "evalJobId": job.get("id"),
            "model": job.get("model"),
            "promptVersion": job.get("promptVersion"),
            "datasetVersion": job.get("datasetVersion"),
            "datasetHash": dataset_hash,
            "commit": job.get("commit"),
            "chatbiAccuracy": job.get("accuracy"),
        }
        log(f"写入 trace 并关联 dataset run: {run_name}")
        trace_ids: list[str] = []
        dataset_item_ids: list[str] = []
        trace_meta: list[dict[str, Any]] = []
        cases: list[dict[str, Any]] = []
        for index, case in enumerate(results, start=1):
            trace_id, metadata = write_trace(sdk, case, job, dataset_hash)
            trace_ids.append(trace_id)
            trace_meta.append(metadata)
            dataset_item_ids.append(f"chatbi-q-{int(case['id']):03d}")
            cases.append(case)
            if index % 20 == 0 or index == len(results):
                log(f"  trace 已创建 {index}/{len(results)}")
        sdk.flush()
        wait_for_traces(rest, trace_ids, args.trace_timeout)

        run_id: str | None = None
        run_items = 0
        for index, (case, trace_id, item_id) in enumerate(
            zip(cases, trace_ids, dataset_item_ids), start=1
        ):
            created = rest.create_run_item(
                run_name=run_name,
                dataset_item_id=item_id,
                trace_id=trace_id,
                run_description=run_description,
                metadata={**run_metadata, "caseId": case.get("id")},
            )
            run_id = run_id or created.get("datasetRunId")
            run_items += 1
            if index % 20 == 0 or index == len(cases):
                log(f"  dataset run item 已创建 {index}/{len(cases)}")
        if run_id is None:
            raise SystemExit("未能从 dataset run item 响应中取得 datasetRunId")

        for case, trace_id in zip(cases, trace_ids):
            # Langfuse accepts exactly one subject per score. Attach the score to
            # the trace; the same trace is linked to the dataset run through the
            # run item created above.
            sdk.create_score(
                trace_id=trace_id,
                name=SCORE_NAME,
                value=1.0 if case.get("passed") else 0.0,
                data_type="NUMERIC",
                comment=case.get("message"),
                metadata=trace_metadata(case, job, dataset_hash),
            )
        sdk.flush()
        log(f"score 已写入: {len(cases)} 条 {SCORE_NAME}")

        # Give the score ingestion a moment before verifying through the API.
        scores: list[dict[str, Any]] = []
        deadline = time.time() + 90
        while time.time() < deadline:
            scores = rest.scores_for_traces(trace_ids, SCORE_NAME)
            if len(scores) >= len(cases):
                break
            log(f"  等待 score 入库: {len(scores)}/{len(cases)}")
            time.sleep(3)

        run_item_count = rest.run_item_count(dataset["id"], run_name)
        avg_accuracy = (
            sum(float(score.get("value") or 0) for score in scores) / len(scores)
            if scores
            else 0.0
        )
        chatbi_accuracy = float(job.get("accuracy") or 0)
        delta = round(avg_accuracy * 100 - chatbi_accuracy, 4)

        spot_trace = rest.trace(trace_ids[0]) or {}
        spot_observations = [
            {
                "id": observation.get("id"),
                "name": observation.get("name"),
                "type": observation.get("type"),
                "model": observation.get("model"),
                "usageDetails": observation.get("usageDetails"),
                "costDetails": observation.get("costDetails"),
                "metadata": observation.get("metadata"),
            }
            for observation in (spot_trace.get("observations") or [])
            if observation.get("type") == "GENERATION"
        ]

        summary = {
            "generatedAt": iso_now(),
            "chatbi": {
                "baseUrl": args.chatbi_url,
                "jobId": job.get("id"),
                "status": job.get("status"),
                "model": job.get("model"),
                "promptVersion": job.get("promptVersion"),
                "datasetVersion": job.get("datasetVersion"),
                "datasetHash": job.get("datasetHash"),
                "commit": job.get("commit"),
                "total": job.get("total"),
                "completed": job.get("completed"),
                "passed": job.get("passed"),
                "accuracyPercent": chatbi_accuracy,
                "categories": job.get("categories"),
            },
            "langfuse": {
                "host": args.langfuse_host,
                "datasetName": args.dataset,
                "datasetId": dataset.get("id"),
                "datasetItems": dataset_item_count,
                "runName": run_name,
                "runId": run_id,
                "runItems": run_item_count,
                "scores": len(scores),
                "scoreName": SCORE_NAME,
                "averageScore": round(avg_accuracy, 6),
                "averageScorePercent": round(avg_accuracy * 100, 2),
                "traces": len(trace_ids),
            },
            "comparison": {
                "chatbiAccuracyPercent": chatbi_accuracy,
                "langfuseAveragePercent": round(avg_accuracy * 100, 2),
                "deltaPoints": delta,
                "consistent": abs(delta) <= 0.01,
            },
            "usage": {
                "model": results[0].get("model") or job.get("model"),
                "promptTokens": sum(int(item.get("promptTokens") or 0) for item in results),
                "completionTokens": sum(int(item.get("completionTokens") or 0) for item in results),
                "costUsd": round(
                    sum(float(item.get("costUsd") or 0) for item in results), 6
                ),
                "casesWithUsage": with_usage,
            },
            "spotCheck": {
                "traceId": trace_ids[0],
                "traceUrl": sdk.get_trace_url(trace_id=trace_ids[0]),
                "generations": spot_observations,
            },
        }

        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(
            json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8"
        )

        log("")
        log("=== Langfuse 评测摘要 ===")
        log(f"dataset items : {dataset_item_count}")
        log(f"run items     : {run_item_count} ({run_name})")
        log(f"scores        : {len(scores)} ({SCORE_NAME})")
        log(f"平均准确率    : {avg_accuracy * 100:.2f}%")
        log(f"ChatBI 准确率 : {chatbi_accuracy:.2f}% (delta {delta:+.2f} 个百分点)")
        log(f"token 用量    : prompt={summary['usage']['promptTokens']} "
            f"completion={summary['usage']['completionTokens']} "
            f"cost=${summary['usage']['costUsd']}")
        log(f"trace 抽查    : {summary['spotCheck']['traceUrl']}")
        log(f"摘要已写入    : {args.output}")

        consistent = abs(delta) <= 0.01
        if len(scores) < len(cases) or run_item_count < len(cases):
            log("警告: Langfuse 计数少于 CaseResult 数量，请检查上面的等待日志")
        return 0 if consistent else 0
    finally:
        sdk.flush()
        sdk.shutdown()
        chatbi.close()
        rest.close()


if __name__ == "__main__":
    sys.exit(main())
