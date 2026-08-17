# Testing and Observability

This repository now includes an open-source-friendly baseline for testing, coverage, and monitoring.

## Test commands

```bash
mvn test
mvn verify
```

- `mvn test`: runs unit tests and web-layer tests.
- `mvn verify`: runs the full validation lifecycle and generates JaCoCo coverage reports.
- `mvn verify` also enforces a minimum bundle line coverage of `45%` as the current baseline.

## Coverage artifacts

After `mvn verify`, coverage outputs are generated in:

- `target/site/jacoco/index.html`
- `target/site/jacoco/jacoco.xml`
- `target/jacoco.exec`

## Observability endpoints

- `GET /actuator/health`
- `GET /actuator/info`
- `GET /actuator/metrics` (requires authentication unless `AI_ACTUATOR_PUBLIC_METRICS=true`)
- `GET /actuator/prometheus` (requires authentication unless `AI_ACTUATOR_PUBLIC_METRICS=true`)

`AI_ACTUATOR_PUBLIC_METRICS` defaults to `false`; the repository `.env.example` sets it to `true` only for the local Compose monitoring stack. Set it to `false` in production and, if metrics should not be exposed at all, set `AI_ACTUATOR_EXPOSED_ENDPOINTS=health,info`.
## Custom business metrics

### Chat and RAG

- `ai.chat.requests.total`
- `ai.chat.latency`
- `ai.rag.search.total`
- `ai.rag.search.latency`
- `ai.rag.results.count`
- `ai.rag.adaptive.total`
- `ai.rag.adaptive.latency`
- `ai.rag.adaptive.rounds`
- `ai.rag.adaptive.chunk.count`

Key tags for adaptive RAG:

- `route`
- `verification`
- `rewritten`
- `end_reason`
- `status`

### Agent trace metrics

- `ai.agent.react.total`
- `ai.agent.react.latency`
- `ai.agent.react.steps`
- `ai.agent.multi.total`
- `ai.agent.multi.latency`
- `ai.agent.multi.subtasks`
- `ai.agent.multi.worker.failures`
- `ai.agent.tool.total`
- `ai.agent.tool.latency`

Tool metrics use bounded tags only:

- `tool`: `query_database|call_external_api|unknown`
- `status`: `success|disabled|invalid_input|timeout|http_error|error|unknown_tool`
- `outcome`: `success|error`

Raw SQL, URL, request body and error text are not written into metric labels.

Key tags for ReAct trace metrics:

- `stop_reason`
- `tool_used`
- `tool_error`
- `status`

Key tags for Multi-Agent trace metrics:

- `stop_reason`
- `single_agent_fallback`
- `synthesis_fallback`
- `status`

### Cache metrics

- `ai.cache.semantic.total` (tags: `result=hit|miss`)
- `ai.cache.semantic.similarity` (distribution, scaled ×100)
- `ai.cache.semantic.latency` (tags: `result=hit|miss`)
- `ai.cache.rag.total` (tags: `result=hit|miss`)
- `ai.cache.operations.total` (tags: `cache`, `operation`, `result`)

### Evaluation reports

- `POST /api/v1/agent/evaluate/export` saves a JSON report under the configured report directory and returns its generated file name. It does not expose the server's absolute report path. The default is `evaluation-reports/`. The repository sample dataset is under `examples/evaluation-datasets/`; dataset files must be under the configured `AI_EVALUATION_DATASET_DIRECTORY`.
- `GET /api/v1/agent/evaluate/history?limit=20` lists the newest report summaries. The service caps the limit at 100 and ignores unrelated files.
- `GET /api/v1/agent/evaluate/history/compare?baseline={fileName}&candidate={fileName}` returns `candidate - baseline` deltas for every common numeric metric. It marks reports as non-comparable when dataset source, size, or top-K values differ.
- History endpoints accept generated report file names only; path traversal and arbitrary local file reads are rejected.
- Evaluation dataset loading resolves the real path and rejects files escaping the configured dataset directory through symbolic links.

### API protection

- High-cost public endpoints (`/chat`, `/react/chat`, streaming chat, and document search) use a Redis fixed-window request limit.
- The same identity receives an estimated-token budget per minute. The estimate is admission control, not provider billing: it combines input characters with a configurable prompt overhead.
- Authenticated requests are keyed by a hash of the authenticated principal; anonymous requests use the remote IP. `X-Forwarded-For` and client-supplied tenant headers are not trusted by this filter.
- Rejections return HTTP `429` with `Retry-After`; oversized input or per-request cost returns HTTP `413`.
- Redis failures fail open by default for local availability. Production deployments can set the two `fail-open` flags to `false` after validating Redis high availability.
### Document ingestion

- `ai.document.ingestion.queued.total`
- `ai.document.ingestion.total`
- `ai.document.ingestion.latency`
- `ai.document.chunk.count`

## Explain-mode observability workflow

Recommended debug flow for open-source demos and interview walkthroughs:

1. Call `POST /api/v1/agent/chat?...&explain=true` to inspect adaptive RAG route, rewrite, verification, evidence, and `reactTrace`.
2. Call `POST /api/v1/agent/react/chat?...&explain=true` to inspect step-by-step ReAct execution.
3. Call `POST /api/v1/agent/multi-agent/execute?...&explain=true` to inspect subtasks, worker results, nested ReAct traces, and synthesis stop reason.
4. Open `/actuator/prometheus` and correlate trace output with `ai.rag.adaptive.*`, `ai.agent.react.*`, `ai.agent.multi.*`, and `ai.agent.tool.*` metrics. Normal ReAct and Multi-Agent API requests also record trace metrics; `explain=true` only controls response detail.

## Adaptive RAG 批量回放

`scripts/replay-adaptive-rag.ps1` 读取 JSON query 文件，逐条调用 `POST /api/v1/agent/rag/debug`，汇总路由、验证等级、结束原因、检索轮次和客户端延迟，并输出 `evidenceStatus`、`evidenceReadyCount` 与 `benchmarkReady`。`benchmarkReady=false` 表示没有可用于 RAG 结论的检索证据。默认报告只保留 query hash、证据元数据和统计结果，不写入 `context` 原文；本地排查私有 query 时可显式增加 `-IncludeQuestions`。

```powershell
powershell -ExecutionPolicy Bypass -File scripts/replay-adaptive-rag.ps1 `
  -AdminApiKey $env:ADMIN_API_KEY `
  -QueryPath examples/evaluation-datasets/rag-sample.json `
  -DelayMilliseconds 200
```

脚本报告只用于决策链回放，不等同于 RAG 召回率评测，也不提供服务端吞吐容量结论。

## Prometheus queries

Example queries for local dashboards:

```promql
sum by (stop_reason) (rate(ai_agent_react_total[5m]))
sum by (stop_reason) (rate(ai_agent_multi_total[5m]))
sum by (route, end_reason) (rate(ai_rag_adaptive_total[5m]))
histogram_quantile(0.99, sum(rate(ai_chat_latency_seconds_bucket[5m])) by (le, mode))
sum by (result) (rate(ai_cache_semantic_total[5m]))
avg(ai_cache_semantic_similarity)
```

## Local Prometheus + Grafana

```bash
docker compose up -d app prometheus grafana
```

Access URLs:

- App: `http://localhost:8081`
- Prometheus: `http://localhost:9090`
- Grafana: `http://localhost:3000`

Grafana credentials must be configured before starting the monitoring stack:

- Username: `GRAFANA_ADMIN_USER` (defaults to `admin`)
- Password: `GRAFANA_ADMIN_PASSWORD` (required; no repository default)

Optional overrides in `.env`:

```dotenv
GRAFANA_ADMIN_USER=admin
GRAFANA_ADMIN_PASSWORD=change-me
```

Grafana auto-loads:

- Prometheus datasource
- Prometheus alert rules from `monitoring/prometheus/alerts.yml`

Prometheus evaluates alerts for chat error rate/P95 latency, Adaptive RAG errors, ReAct tool errors, and Multi-Agent worker failures. An Alertmanager is intentionally not bundled; connect one in deployment environments that need notifications.
- `AI Agent Platform Overview` dashboard

Recommended dashboard grouping:

- Adaptive RAG routing and end reasons
- ReAct stop reasons and step counts
- Multi-Agent subtask counts and worker failure counts
- Tool call status distribution and P95 latency
- Chat latency and cache hit distribution

## CI artifacts

The GitHub Actions workflow uploads:

- packaged application jar
- Surefire test reports
- JaCoCo coverage report

Current CI baseline:

- JaCoCo bundle line coverage must be at least `45%`

This makes it easier for contributors to inspect failures and quality signals directly from CI.
