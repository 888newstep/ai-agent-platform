# AI Agent Platform

基于 **Spring Boot 3.5 + LangChain4j 0.34** 构建的 AI Agent 服务端框架，内置 ReAct 推理循环、多路召回 RAG、语义缓存与工具调用，开箱即用、可观测、可评测，适合作为企业级智能问答 / 客服系统的后端底座。

[![CI/CD Pipeline](https://github.com/888newstep/ai-agent-platform/actions/workflows/ci.yml/badge.svg)](https://github.com/888newstep/ai-agent-platform/actions/workflows/ci.yml)
[![JDK 17](https://img.shields.io/badge/JDK-17-blue.svg)](https://adoptium.net/)
[![Spring Boot 3.5](https://img.shields.io/badge/Spring%20Boot-3.5-6DB33F.svg?logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![LangChain4j 0.34](https://img.shields.io/badge/LangChain4j-0.34-1C3C3C.svg)](https://docs.langchain4j.dev/)
[![Jetty 12](https://img.shields.io/badge/Jetty-12-FC390E.svg)](https://eclipse.dev/jetty/)
[![GitHub Stars](https://img.shields.io/github/stars/888newstep/ai-agent-platform?style=flat)](https://github.com/888newstep/ai-agent-platform/stargazers)
[![Last Commit](https://img.shields.io/github/last-commit/888newstep/ai-agent-platform)](https://github.com/888newstep/ai-agent-platform/commits/main)
[![License](https://img.shields.io/github/license/888newstep/ai-agent-platform)](LICENSE)

---

## 它解决什么问题

构建一个生产可用的 Agent 服务，通常会卡在几个地方：**召回不准**（答非所问）、**成本失控**（每次请求都调一次大模型）、**不可观测**（出了问题不知道哪一环挂了）、**难以验证**（上线前不知道效果到底怎么样）。本项目把这几件事做成了开箱即用的工程能力：

- **答得准**：多路召回（向量 + 关键词）+ RRF 融合 + 证据门禁，回答必须有知识库证据支撑
- **省成本**：语义缓存 + 多模型路由 + 本地模型降级，相似问题直接命中缓存不再重复调用
- **看得见**：Prometheus / Grafana 观测整条链路，每个环节都有指标
- **可验证**：内置 RAG 评测服务，用独立数据集量化召回率 / 准确率 / 延迟

## 核心能力

| 能力 | 做了什么 | 效果 |
|------|---------|------|
| **ReAct 推理循环** | Thought → Action → Observation → Answer，带死循环 / 超时 / 步数三重防护 | 复杂问题可拆解执行，稳定收敛 |
| **Adaptive RAG** | 查询路由 + 改写 + 多轮检索 + 结果自验证 | FAQ 域内 R@1 达到 **98.3%**（multi-gold，120 例） |
| **多路召回** | Milvus 向量 + BM25 关键词 + RRF(k=60) 融合 | 单 gold 基线 R@1 47.5% → R@5 62.5% |
| **语义缓存** | embedding 余弦相似度（0.92 阈值，24h TTL） | 相似问题命中缓存，显著降低 API 成本 |
| **证据门禁** | 回答前校验检索证据等级，证据不足转人工 | 客服场景避免模型"编造"答案 |
| **多模型路由** | DeepSeek / 通义千问 / 豆包 / Qwen3-Flash / Ollama 策略切换 | 主模型故障自动降级本地模型 |
| **工具调用** | 统一注册表：查库（白名单表）+ 外部 API（域名白名单） | Agent 具备执行动作能力 |
| **可观测** | Micrometer 自定义指标 + Prometheus + Grafana | 聊天 / 检索 / 入库延迟一目了然 |

## 架构总览

```
┌─────────────────────────────────────────────────────────────────┐
│                          AI Agent Platform                      │
│  ┌──────────────┐ ┌───────────────────┐ ┌─────────────────────┐ │
│  │   Chat API   │ │    ReAct API      │ │   Document API      │ │
│  └──────┬───────┘ └─────────┬─────────┘ └──────────┬──────────┘ │
│  ┌──────┴───────┐ ┌─────────┴─────────┐ ┌──────────┴──────────┐ │
│  │ AiAgentSrv    │ │   ReActAgent      │ │   DocumentService   │ │
│  └──────┬───────┘ └─────────┬─────────┘ └──────────┬──────────┘ │
│  ┌──────┴──────────────────────────────────────────┴──────────┐ │
│  │               SemanticCacheService (余弦相似度缓存)         │ │
│  └──────┬──────────────────────────────────────────┬──────────┘ │
│  ┌──────┴──────┐ MultiRecallService  ┌─────────────┴──────────┐ │
│  │ Vector(ML)  │  + BM25  + RRF 融合  │  Adaptive RAG Router  │ │
│  └──────┬──────┘                     └─────────────┬──────────┘ │
│  ┌──────┴──────────────────────┐  ┌───────────────┴──────────┐  │
│  │ ToolService(注册表自动发现)  │  │ LongContextManager(摘要)  │  │
│  └──────┬──────────────────────┘  └───────────────┬──────────┘  │
│  ┌──────┴─────┐ ┌────────┐ ┌────────┐ ┌───────────┴────────┐    │
│  │   MySQL    │ │ Redis  │ │ Milvus │ │  AI Models(策略切换)│    │
│  └────────────┘ └────────┘ └────────┘ └────────────────────┘    │
└─────────────────────────────────────────────────────────────────┘
```

**核心链路**：请求进入 → 语义缓存命中直接返回（降本）→ 未命中走 ReAct / Adaptive RAG（向量 + BM25 + RRF，工具调用）→ 长会话由 LongContextManager 滑动窗口 + 摘要压缩 → 结果回写缓存并流式（SSE）返回。

> 完整架构设计请参阅 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)

## 快速开始

### 前置条件

- JDK 17+
- Docker & Docker Compose（推荐）
- Maven 3.9+

### 1. 克隆项目

```bash
git clone https://github.com/888newstep/ai-agent-platform.git
cd ai-agent-platform
```

### 2. 配置环境变量

```bash
cp .env.example .env
# 编辑 .env 填入你的 API 密钥（模型 / 嵌入 / Milvus 等）
```

### 3. 启动

全本地依赖（一条命令启动 MySQL + Redis + Milvus + 应用）：

```bash
docker compose up -d
```

也支持**混合拓扑**：本机跑应用 + MySQL + Redis，Milvus 用云端实例（通过 `MILVUS_HOST` 等环境变量指定）。启动前可先跑预检脚本确认依赖可达：

```powershell
.\scripts\check-infrastructure.ps1
```

### 4. 验证

```bash
curl http://localhost:8081/api/v1/agent/health
# 返回: {"status":"UP","service":"AI Customer Service Agent","version":"1.0.0"}
```

## 使用示例

创建会话并聊天：

```bash
curl -X POST http://localhost:8081/api/v1/agent/session

curl -X POST "http://localhost:8081/api/v1/agent/chat?sessionId={sessionId}&question=你好&useRag=true"
```

ReAct 模式（推理 + 工具调用）：

```bash
curl -X POST "http://localhost:8081/api/v1/agent/react/chat?sessionId={sessionId}&question=查询数据库中的用户数量&useRag=true"
```

流式聊天（SSE）：

```bash
curl -N "http://localhost:8081/api/v1/agent/chat/stream?sessionId={sessionId}&question=请详细介绍RAG技术"
```

上传文档建知识库：

```bash
curl -X POST -F "file=@文档.pdf" http://localhost:8081/api/v1/agent/document/upload
```

客服问答（强制证据门禁，需 JWT）：

```bash
curl -X POST "http://localhost:8081/api/v1/customer-support/chat" \
  -H "Authorization: Bearer ${JWT_TOKEN}" \
  -H "Idempotency-Key: cs-chat-001" \
  -H "Content-Type: application/json" \
  -d '{"sessionId":"{sessionId}","question":"订单如何申请退款？"}'
```

## 评测与质量

项目内置一套可复现的 RAG 评测体系，用独立数据集量化检索与回答质量，避免"感觉还行"：

- **FAQ 检索（multi-gold）**：120 例、6 类别，R@1 **98.3%**、六类均 ≥ 91.67%，P95 181ms
- **单 gold 基线**：R@1 47.5%、R@5 62.5%（用于版本间回归对比）
- **测试**：372 个单元 / 集成测试，JaCoCo 行覆盖率 **64%**（门禁 45%）
- **压测**：JMeter 参数化压测计划，可量化吞吐与延迟分布

详细评测方法与复现步骤见 [docs/RAG_BENCHMARK.md](docs/RAG_BENCHMARK.md)、[docs/EVIDENCE_VERIFICATION.md](docs/EVIDENCE_VERIFICATION.md)，运行脚本 `scripts/run-rag-evaluation.ps1`。

```bash
# 用最小示例集跑一次评测（topK=1,3,5）
curl -X POST \
  -H "X-Admin-Api-Key: ${ADMIN_API_KEY}" \
  "http://localhost:8081/api/v1/agent/evaluate/export?datasetPath=examples/evaluation-datasets/rag-sample.json&topKs=1,3,5"
```

> 正式评测请使用独立人工标注数据集（`independent-human-labeled`），不要用公开样例或 smoke 数据冒充业务基线。

## 安全设计

| 层 | 措施 |
|----|------|
| 认证 | JWT 无状态鉴权（BCrypt 存储）、`X-Admin-Api-Key` 管理端密钥 |
| 限流 | Redis 固定窗口限流（默认 30 req/min），按 IP 隔离 |
| 预算 | 单请求 Token 估算上限 + 每分钟预算，超限 fail-open |
| 工具安全 | 数据库白名单表、外部 API 域名白名单 + 拒绝私网地址 |
| 证据门禁 | 客服回答必须通过证据等级校验，否则转人工 |

## 配置说明

模型切换（`application.yml`）：

```yaml
ai:
  model:
    provider: deepseek   # 可选: deepseek, qianwen, doubao, qwen3-flash, local
  embedding:
    provider: siliconflow # 可选: local, local-qwen3, siliconflow
```

所有敏感配置通过环境变量注入（参考 `.env.example`）。**生产环境务必设置 `JWT_SECRET` 和 `ADMIN_API_KEY`。**

## 项目结构

```
src/main/java/com/aiagent/
├── agent/
│   ├── api/                         # Agent REST API
│   ├── application/                 # 普通聊天、ReAct、Multi-Agent 编排
│   └── infrastructure/tool/         # 数据库与外部 API 工具
├── infrastructure/
│   ├── cache/                       # 语义缓存与 RAG 缓存
│   ├── config/                      # 模型、Milvus、安全与限流配置
│   └── metrics/                     # Micrometer 业务指标
├── knowledge/
│   ├── application/                 # 文档上传、解析与入库
│   └── infrastructure/              # Parser、Splitter、VectorStore
├── rag/application/                 # Adaptive RAG、Multi-Recall、评测
├── chat/                            # 会话与 SSE 流式响应
└── shared/                          # 公共响应体与异常处理
```

## 数据来源

项目内的电商客服问答演示数据来自 [ModelScope 开源数据集 E_commerce_Customer_Service](https://modelscope.cn/datasets/modelscope_mp_677764216/E_commerce_Customer_Service)（Apache 2.0），清洗去重后入库 **100,349 条**唯一有效 QA 对（Milvus + MySQL）。版权归原提供方所有，商用请遵守 Apache 2.0 条款。

## 更新日志

### [v1.0.0] — 2026-08-17（首个正式版）

- **Agent 核心**：ReAct 推理循环（最大 10 步 / 3 分钟超时 / 连续 3 次相同观测自动终止）；Supervisor + Worker 多智能体编排（WorkStealingPool、按序聚合输出）
- **RAG**：Adaptive RAG（查询路由阈值可配、改写、多轮检索、自验证）；多路召回（Milvus 向量 + BM25 + RRF k=60，候选池 top-200）
- **成本控制**：语义缓存（0.92 阈值 / 24h TTL）；多模型路由 + 故障自动降级本地模型
- **长上下文**：滑动窗口（size 10）+ 周期摘要压缩（每 5 条消息），控制在模型上下文内
- **安全**：JWT + Admin API Key + Redis 限流 + Token 预算 + 工具白名单
- **可观测**：Prometheus / Grafana 预置看板；`ai.chat.latency` / `ai.rag.search.latency` 等自定义指标
- **工程**：372 测试 + JaCoCo 64% 门禁 + GitHub Actions CI（build → test → coverage → Docker）；Docker Compose 一键编排 6 服务（MySQL / Redis / Milvus / App / Prometheus / Grafana）；Flyway 版本化迁移
- **领域模块**：电商知识批量导入（batch=18 适配 8192 token 嵌入限制，Milvus + MySQL 双写）；客服数据导入（断点续跑）；文档管理（PDF/Word/Markdown/TXT 解析，500 字分块 + 50 重叠）

## 贡献

欢迎任何形式的贡献——提 Issue、修 Bug、补文档、加功能都行。请先阅读 [CONTRIBUTING.md](CONTRIBUTING.md)。

特别感谢 [codeAnqiang-ma](https://github.com/codeAnqiang-ma) 修复了 LongContextManager 会话摘要触发逻辑并提交 PR（详见 [PR #2](https://github.com/888newstep/ai-agent-platform/pull/2)）。

## License

Apache License 2.0
