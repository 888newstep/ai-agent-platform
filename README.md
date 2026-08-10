# AI Agent Platform

基于 Spring Boot 3 + LangChain4j 构建的企业级 AI Agent 平台，集成 ReAct 推理循环、多路召回 RAG、语义缓存和工具调用框架。

[![CI/CD Pipeline](https://github.com/888newstep/ai-agent-platform/actions/workflows/ci.yml/badge.svg)](https://github.com/888newstep/ai-agent-platform/actions/workflows/ci.yml)
[![JDK 17](https://img.shields.io/badge/JDK-17-blue.svg)](https://adoptium.net/)
[![Spring Boot 3](https://img.shields.io/badge/Spring%20Boot-3.5-green.svg)](https://spring.io/projects/spring-boot)
[![License](https://img.shields.io/badge/License-Apache%202.0-orange.svg)](LICENSE)

## Open Source Quality Baseline

- Test lifecycle: `mvn test` / `mvn verify`
- Coverage report: `target/site/jacoco/index.html`
- Metrics endpoints: `/actuator/health`, `/actuator/metrics`, `/actuator/prometheus`
- Monitoring stack: `docker compose up -d app prometheus grafana`
- Details: `docs/observability.md`

> **🎯 适用人群**
> - 正在准备大厂 Java / AI 岗位面试的求职者（覆盖高频面试考点）
> - 需要快速搭建 AI 客服系统的中小企业或独立开发者
> - 想学习 RAG + Agent 完整落地实践的技术爱好者
> - 需要可扩展 AI Agent 框架的产品开发者

---

## 功能特性

- **ReAct Agent 循环** — Thought → Action → Observation → Final Answer 推理循环，含死循环防护和超时控制
- **多路召回 RAG** — 向量检索（Milvus）+ BM25 关键词检索 + RRF 融合排序
- **语义缓存** — 基于 embedding 余弦相似度，自动缓存相似问题回答，降低 API 成本
- **API 保护** — Redis 分布式固定窗口限流 + 估算 Token 预算，按认证主体/IP 隔离高成本请求
- **多模型支持** — 策略模式动态切换（DeepSeek / 通义千问 / 豆包 / Qwen3-Flash / Ollama 本地）
- **工具调用框架** — 数据库查询、外部 API 调用，注册表模式自动发现
- **流式输出** — SSE 实时推送，5 分钟超时保护
- **会话管理** — Redis 存储会话上下文，24 小时 TTL 自动过期
- **Docker 一键部署** — 4 个服务编排（MySQL + Redis + Milvus + App）

---

## 技术栈

| 层级 | 技术 |
|------|------|
| 框架 | Spring Boot 3.5, LangChain4j 0.34 |
| 语言 | Java 17 |
| 数据库 | MySQL 8.0, Redis 7, Milvus 2.4 |
| AI 模型 | DeepSeek, 通义千问, 豆包, Qwen3-Flash, Ollama |
| 嵌入模型 | BGE-M3 (SiliconFlow API) |
| 部署 | Docker, Docker Compose |
| CI/CD | GitHub Actions |

---

## 快速开始

### 前置条件

- JDK 17+
- Docker & Docker Compose (推荐)
- Maven 3.9+

### 1. 克隆项目

```bash
git clone https://github.com/888newstep/ai-agent-platform.git
cd ai-agent-platform
```

### 2. 配置环境变量

```bash
cp .env.example .env
# 编辑 .env 填入你的 API 密钥
```

### 3. 启动（Docker 一键部署）

```bash
docker compose up -d
```

### 4. 验证

```bash
curl http://localhost:8081/api/v1/agent/health
# 返回: {"status":"UP","mode":"react"}
```

---

## API 使用示例

### 创建会话

```bash
curl -X POST http://localhost:8081/api/v1/agent/session
```

### 普通聊天

```bash
curl -X POST "http://localhost:8081/api/v1/agent/chat?sessionId={sessionId}&question=你好&useRag=true"
```

### ReAct 模式聊天（推理 + 工具调用）

```bash
curl -X POST "http://localhost:8081/api/v1/agent/react/chat?sessionId={sessionId}&question=查询数据库中的用户数量&useRag=true"
```

### 流式聊天

```bash
curl -N "http://localhost:8081/api/v1/agent/chat/stream?sessionId={sessionId}&question=请详细介绍RAG技术"
```

### 上传文档

```bash
curl -X POST -F "file=@文档.pdf" http://localhost:8081/api/v1/agent/document/upload
```

### 评测报告历史

评测接口需要管理员凭证。导出报告后，可以列出最近报告并比较两次评测的指标变化：

```bash
curl -H "X-Admin-Api-Key: ${ADMIN_API_KEY}" \
  "http://localhost:8081/api/v1/agent/evaluate/history?limit=20"

curl -H "X-Admin-Api-Key: ${ADMIN_API_KEY}" \
  "http://localhost:8081/api/v1/agent/evaluate/history/compare?baseline={fileName}&candidate={fileName}"
```

比较结果中的 `metricDeltas` 使用 `candidate - baseline`，并在数据集来源、样本数或 `topKs` 不一致时标记为不可直接比较。

### 可复现 RAG 评测

仓库提供最小示例数据集 `examples/evaluation-datasets/rag-sample.json`，默认配置已指向该目录。数据集中的 `relevantDocIds` 是评测基准的 chunk ID；只有先导入包含这些 ID 的文档，召回率和准确率才具有业务意义。真实数据请通过 `AI_EVALUATION_DATASET_DIRECTORY` 指向本地私有目录，避免提交到 GitHub。

```bash
curl -X POST \
  -H "X-Admin-Api-Key: ${ADMIN_API_KEY}" \
  "http://localhost:8081/api/v1/agent/evaluate/export?datasetPath=examples/evaluation-datasets/rag-sample.json&topKs=1,3,5"
```
---

## 架构设计

```
┌─────────────────────────────────────────────────────────────┐
│                    AI Agent Platform                         │
├─────────────────────────────────────────────────────────────┤
│  Controller Layer                                           │
│  ┌─────────────┐ ┌──────────────┐ ┌──────────────────┐     │
│  │ Chat API     │ │ ReAct API    │ │ Document API     │     │
│  └──────┬───────┘ └──────┬───────┘ └────────┬─────────┘     │
├─────────┼────────────────┼──────────────────┼───────────────┤
│  Service Layer           │                  │               │
│  ┌──────┴────────┐ ┌─────┴──────┐ ┌────────┴──────────┐    │
│  │ AiAgentService│ │ ReActAgent  │ │ DocumentService   │    │
│  └──────┬────────┘ └─────┬──────┘ └────────┬──────────┘    │
│         │                │                  │               │
│  ┌──────┴────────────────┴──────────────────┴──────────┐    │
│  │              SemanticCacheService                    │    │
│  └──────────────────────┬──────────────────────────────┘    │
├─────────────────────────┼──────────────────────────────────┤
│  Retrieval Layer        │                                   │
│  ┌──────────────────────┴──────────────────────────────┐    │
│  │              MultiRecallService                     │    │
│  │  ┌────────────────┐  ┌──────────────────────────┐   │    │
│  │  │ Vector Search  │  │  BM25 Keyword Search     │   │    │
│  │  │ (Milvus)       │  │  (Bm25Search)            │   │    │
│  │  └────────┬───────┘  └──────────┬───────────────┘   │    │
│  │           └──────────┬──────────┘                    │    │
│  │                  RRF Fusion                          │    │
│  └──────────────────────────────────────────────────────┘    │
├──────────────────────────────────────────────────────────────┤
│  Tool Layer                                                   │
│  ┌──────────────────────────────────────────────────────┐    │
│  │  ToolService (query_database / call_external_api)    │    │
│  └──────────────────────────────────────────────────────┘    │
├──────────────────────────────────────────────────────────────┤
│  Infrastructure Layer                                        │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────────┐    │
│  │  MySQL   │ │  Redis   │ │  Milvus  │ │  AI Models   │    │
│  └──────────┘ └──────────┘ └──────────┘ └──────────────┘    │
└──────────────────────────────────────────────────────────────┘
```

> 详细架构设计请参阅 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)

---

## ReAct Agent 详解

### 核心循环

```
Thought: 分析问题，决定下一步行动
    ↓
Action: 选择工具（query_database / call_external_api）
    ↓
Action Input: 工具参数
    ↓
Observation: 工具执行结果
    ↓
（重复以上步骤，直到得到足够信息）
    ↓
Final Answer: 给出最终回答
```

### 安全机制

| 机制 | 说明 |
|------|------|
| 最大迭代步数 | 默认 10 步，防止无限循环 |
| 超时控制 | 整体任务 3 分钟超时 |
| 死循环检测 | 相同 Observation 连续出现 3 次则终止 |
| 异常捕获 | LLM 调用或工具执行失败时优雅降级 |

---

## 多路召回 RAG

### 召回流程

1. **向量检索** — Milvus 语义相似度检索，捕获语义相近的文档
2. **BM25 关键词检索** — 关键词精确匹配，捕获包含特定术语的文档
3. **RRF 融合** — 倒数排名融合算法，合并两路结果

### RRF 公式

```
score(d) = Σ 1/(k + rank_i(d))
```

其中 `k=60`，`rank_i(d)` 是文档 d 在第 i 路检索中的排名。

---

## 项目结构

```
src/main/java/com/aiagent/
├── agent/
│   ├── AiAgentService.java      # AI 聊天服务（集成长上下文管理 + ReAct + 语义缓存）
│   ├── ReActAgent.java          # ReAct 推理循环
│   └── MultiAgentService.java   # 多智能体协作（Supervisor + Worker）
├── cache/
│   └── SemanticCacheService.java # 语义缓存（降低 API 成本）
├── config/
│   ├── AiModelConfig.java       # 多模型配置（策略模式）
│   ├── AiProperties.java        # 配置属性绑定
│   └── MilvusInitConfig.java    # Milvus 初始化
├── controller/
│   └── AiAgentController.java   # REST API 控制器
├── document/
│   ├── DocumentService.java     # 文档上传与检索
│   └── parser/                  # 多格式文档解析器
├── evaluation/
│   ├── RagEvaluationService.java # RAG 效果评估（召回率/准确率/延迟）
│   └── EvaluationReportHistoryService.java # 评测报告历史与指标对比
├── memory/
│   └── LongContextManager.java  # 长上下文管理（滑动窗口 + 摘要压缩 + 历史检索）
├── retrieval/
│   ├── MultiRecallService.java  # 多路召回 + RRF 融合
│   └── Bm25Search.java          # BM25 关键词检索
├── tool/
│   └── ToolService.java         # 工具调用框架
└── vectorstore/
    └── MilvusVectorStoreService.java  # Milvus 向量存储
```

---

## 配置说明

> 安全提示：启动前必须通过环境变量设置 `JWT_SECRET` 和 `ADMIN_API_KEY`。仓库中的 `.env.example` 仅是占位模板，不要直接用于生产环境。

### 模型切换

在 `application.yml` 中修改 `ai.model.provider`：

```yaml
ai:
  model:
    provider: deepseek   # 可选: deepseek, qianwen, doubao, qwen3-flash, local
```

### 嵌入模型切换

```yaml
ai:
  embedding:
    provider: siliconflow  # 可选: local, local-qwen3, siliconflow
```

### 环境变量

参考 `.env.example` 文件，所有敏感配置通过环境变量注入。

---

## 面试价值

该项目适用于以下面试场景：

### Java 后端岗

| 知识点 | 项目体现 |
|--------|---------|
| Spring Boot 启动流程 | AiModelConfig 自动配置、@ConditionalOnProperty |
| 策略模式 | 多模型动态切换 |
| Redis 缓存 | 会话管理、语义缓存 |
| 数据库优化 | JPA + 连接池配置 |
| Docker 部署 | 4 服务编排、健康检查 |

### AI 应用岗

| 知识点 | 项目体现 |
|--------|---------|
| ReAct Agent | 完整推理循环、死循环防护、超时控制 |
| 长上下文管理 (Q174) | 滑动窗口 + 摘要压缩 + 历史检索 |
| RAG 效果评估 (Q173) | 召回率/准确率/F1/延迟量化评估 |
| RAG 优化 | 多路召回 + RRF 融合 |
| 成本控制 | 语义缓存、本地模型降级 |
| Embedding | SiliconFlow BGE-M3 接入 |
| 向量数据库 | Milvus 集合创建、索引构建 |
| 多智能体 (Q147) | Supervisor + Worker 协作模式 |
| SSE 流式输出 (Q151-153) | 心跳机制 + 虚拟线程管理 |

---

## License

Apache License 2.0
