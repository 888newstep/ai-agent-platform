# NewAgent 技术优化计划

## 目标

将项目从功能型 RAG/Agent Demo 提升为具备可解释、可量化、可观测、可复现能力的开源 AI Agent 平台。

## 技术主线

1. Adaptive RAG：路由、查询改写、多轮检索与结果验证。
2. Multi-Recall：向量检索、BM25 与 RRF 融合排序。
3. ReAct Agent：工具调用、超时、死循环熔断与执行轨迹。
4. Evaluation：数据集评测、参数对比与报告导出。
5. Observability：业务指标、Trace、Prometheus 与 Grafana。

## 已完成

### P0：可解释执行

- 普通 Agent 和 ReAct 接口支持 `explain=true`。
- 返回路由、查询改写、验证、证据和 ReAct 执行轨迹。
- 保持默认响应结构兼容已有调用方。

### P1：Trace 与指标

- 新增 ReAct 和 Multi-Agent 结构化 Trace DTO。
- 记录 stop reason、步骤数、Worker 失败数和降级状态。
- 通过 `/actuator/prometheus` 暴露 Agent 执行指标。

### P2：缓存与评测

- 记录语义缓存和 RAG 缓存命中率、相似度与延迟。
- 支持评测报告 JSON 导出。
- 增加评测报告历史列表与两份报告的指标差值对比。
- 数据集读取限制在配置目录，报告写入独立目录。

## 当前接口

- `POST /api/v1/agent/chat?explain=true`
- `POST /api/v1/agent/react/chat?explain=true`
- `POST /api/v1/agent/multi-agent/execute?explain=true`
- `POST /api/v1/agent/evaluate`
- `POST /api/v1/agent/evaluate/compare`
- `POST /api/v1/agent/evaluate/export`
- `GET /api/v1/agent/evaluate/history?limit=20`
- `GET /api/v1/agent/evaluate/history/compare?baseline={fileName}&candidate={fileName}`

## 配置约束

- `AI_EVALUATION_DATASET_DIRECTORY`：评测数据集目录，默认 `./evaluation-datasets`。
- `AI_EVALUATION_REPORT_DIRECTORY`：评测报告目录，默认 `./evaluation-reports`。
- `JWT_SECRET` 和 `ADMIN_API_KEY` 必须通过环境变量配置。
- 生产环境应关闭公开 Actuator 指标，并配置明确的 CORS 白名单。

## 验证标准

- `mvn test` 全量测试通过。
- `mvn verify` 通过 JaCoCo 45% 行覆盖率门禁。
- 新增接口必须有控制器测试，核心指标必须有埋点或行为验证。
- 文档中的接口、指标和配置必须与当前实现一致。

## 后续方向

- 评测报告历史对比已完成；可视化页面暂不引入，先通过 JSON 接口和 Grafana 面板复用数据。
- 为关键指标补充 Grafana 面板和告警规则。
- 增加公开 API 的请求限流、成本预算和租户隔离。
- 将评测数据集与报告纳入独立的示例目录，避免混入运行时数据。
