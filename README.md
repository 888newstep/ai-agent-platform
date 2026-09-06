# AI Agent Platform

基于 **Spring Boot 3.5 + LangChain4j 0.34** 构建的 Java 17 AI Agent 服务端。围绕企业知识问答与智能客服，提供 ReAct、Hybrid RAG、Function Calling、语义缓存、多模型路由、SSE 流式响应、评测与可观测能力。

> 不是只展示 Prompt 的 Demo：项目覆盖文档入库、混合召回、排序融合、证据门禁、模型生成、线上观测和离线评测的完整工程链路。

[![CI/CD Pipeline](https://github.com/888newstep/ai-agent-platform/actions/workflows/ci.yml/badge.svg)](https://github.com/888newstep/ai-agent-platform/actions/workflows/ci.yml)
[![JDK 17](https://img.shields.io/badge/JDK-17-blue.svg)](https://adoptium.net/)
[![Spring Boot 3.5](https://img.shields.io/badge/Spring%20Boot-3.5-6DB33F.svg?logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![LangChain4j 0.34](https://img.shields.io/badge/LangChain4j-0.34-1C3C3C.svg)](https://docs.langchain4j.dev/)
[![Jetty 12](https://img.shields.io/badge/Jetty-12-FC390E.svg)](https://eclipse.dev/jetty/)
[![GitHub Stars](https://img.shields.io/github/stars/888newstep/ai-agent-platform?style=flat)](https://github.com/888newstep/ai-agent-platform/stargazers)
[![Last Commit](https://img.shields.io/github/last-commit/888newstep/ai-agent-platform)](https://github.com/888newstep/ai-agent-platform/commits/main)
[![License](https://img.shields.io/badge/License-Apache%202.0-orange.svg)](LICENSE)

[快速开始](#快速开始) · [架构设计](docs/ARCHITECTURE.md) · [评测证据](docs/EVIDENCE_VERIFICATION.md) · [安全说明](SECURITY.md) · [参与贡献](CONTRIBUTING.md)

如果这个项目对你有帮助，欢迎点一个 **Star**。它会帮助更多 Java / AI Agent 开发者发现这个项目，也会推动后续持续完善评测集、性能基线和工程文档。

---

## 为什么值得关注

许多 Agent 示例停留在“调用一次模型并输出答案”。本项目更关注从原型走向可验证服务时真正遇到的问题：**Agent 如何停止、检索如何融合、证据不足如何拒答、模型故障如何降级、效果如何复现**。

构建一个可用的 Agent 服务，通常会卡在几个地方：**召回不准**（答非所问）、**成本失控**（每次请求都调一次大模型）、**不可观测**（出了问题不知道哪一环挂了）、**难以验证**（上线前不知道效果到底怎么样）。本项目将这些问题实现为可运行、可测试、可复现的工程能力：

- **答得准**：多路召回（向量 + 关键词）+ RRF 融合 + 证据门禁，回答必须有知识库证据支撑
- **省成本**：语义缓存 + 多模型路由 + 本地模型降级，相似问题直接命中缓存不再重复调用
- **看得见**：Prometheus / Grafana 观测整条链路，每个环节都有指标
- **可验证**：内置 RAG 评测服务，用独立数据集量化召回率 / 准确率 / 延迟

### 适合谁

- 想系统学习 **Java + AI Agent + RAG** 完整工程链路的开发者
- 需要搭建企业知识库、智能客服或内部问答服务的团队
- 关注检索评测、性能、安全与可观测，而不满足于简单模型调用的工程实践者

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

## 三分钟体验

启动完成后，可以直接验证会话、RAG 问答和 SSE 流式输出：

```bash
# 创建会话并进行 RAG 问答
curl -X POST http://localhost:8081/api/v1/agent/session
curl -X POST "http://localhost:8081/api/v1/agent/chat?sessionId={sessionId}&question=订单如何申请退款？&useRag=true"

# SSE 流式响应
curl -N "http://localhost:8081/api/v1/agent/chat/stream?sessionId={sessionId}&question=请介绍RAG技术"
```

更多接口（ReAct、文档上传、JWT 客服问答）可通过启动后的 Swagger/OpenAPI 文档查看。

### 典型处理链路

```text
用户问题
  → 语义缓存查询
  → Query 路由 / 改写
  → Milvus 向量召回 + BM25 关键词召回
  → RRF 融合排序
  → 证据阈值与安全门禁
  → LLM 生成 / Function Calling
  → SSE 流式返回 + 指标记录
```

## 可复现的评测证据

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

### 指标口径说明

- **98.3%** 是 FAQ 域内、multi-gold 判定下的 R@1，不代表任意开放领域问题的准确率。
- 独立 hold-out 复验的真实泛化区间约为 **88%–93%**；项目同时保留单 gold 原始基线，避免只展示最优数字。
- 指标快照和口径说明均提交到仓库，便于复核，而不是仅在 README 中声明结果。

## 安全与工程化

| 层 | 措施 |
|----|------|
| 认证 | JWT 无状态鉴权（BCrypt 存储）、`X-Admin-Api-Key` 管理端密钥 |
| 限流 | Redis 固定窗口限流（默认 30 req/min），按 IP 隔离 |
| 预算 | 单请求 Token 估算上限 + 每分钟预算，超限 fail-open |
| 工具安全 | 数据库白名单表、外部 API 域名白名单 + 拒绝私网地址 |
| 证据门禁 | 客服回答必须通过证据等级校验，否则转人工 |
| Agent 收敛 | 最大步数、总超时、重复 Observation 检测，避免无限循环 |
| 文件与评测 | 上传类型 / Magic Bytes / 大小预算；评测路径沙箱与并发隔离 |

更多安全边界、攻击面和本地非破坏性探针见 [SECURITY.md](SECURITY.md) 与 [安全加固说明](docs/security-attack-simulation-and-hardening.md)。

## 技术栈

| 分层 | 技术 |
|------|------|
| 应用与 Agent | Java 17、Spring Boot 3.5、LangChain4j 0.34、Jetty 12 |
| 检索与存储 | Milvus、BM25、RRF、MySQL、Redis |
| 模型接入 | DeepSeek、通义千问、豆包、Qwen3-Flash、Ollama |
| 工程能力 | JWT、Flyway、Micrometer、Prometheus、Grafana、Docker Compose |
| 质量保障 | JUnit 5、Mockito、JaCoCo、JMeter、GitHub Actions |

## 配置说明

模型与嵌入服务可在 `application.yml` 中切换；敏感配置统一通过环境变量注入，完整项见 [`.env.example`](.env.example)。**生产环境务必设置 `JWT_SECRET` 和 `ADMIN_API_KEY`。**

## 数据来源

项目内的电商客服问答演示数据来自 [ModelScope 开源数据集 E_commerce_Customer_Service](https://modelscope.cn/datasets/modelscope_mp_677764216/E_commerce_Customer_Service)（Apache 2.0），清洗去重后入库 **100,349 条**唯一有效 QA 对（Milvus + MySQL）。版权归原提供方所有，商用请遵守 Apache 2.0 条款。

## 路线图

- [x] ReAct Agent、工具注册与循环收敛保护
- [x] Milvus + BM25 + RRF 混合检索
- [x] 语义缓存、多模型路由与本地模型降级
- [x] RAG 离线评测、指标快照与性能压测
- [x] JWT、限流、预算、上传与工具调用安全边界
- [ ] 增加更多独立 hold-out 数据与自动化回归报告
- [ ] 完善 CrossEncoder 可插拔精排和模型路由评测
- [ ] 补充前端演示与完整部署案例

当前主线为 **v1.0.0**。详细变更以 [Releases](https://github.com/888newstep/ai-agent-platform/releases) 和 Git 提交记录为准。

## 贡献与支持

欢迎通过 [Issue](https://github.com/888newstep/ai-agent-platform/issues) 反馈问题或建议，也欢迎修 Bug、补文档和扩展能力。提交代码前请阅读 [CONTRIBUTING.md](CONTRIBUTING.md)。

- 觉得项目有参考价值：点击右上角 **Star**，方便以后继续查看
- 遇到问题：提交 Issue，并附上环境、复现步骤和关键日志
- 希望参与：从文档、测试或小范围修复开始提交 Pull Request

特别感谢 [codeAnqiang-ma](https://github.com/codeAnqiang-ma) 修复了 LongContextManager 会话摘要触发逻辑并提交 PR（详见 [PR #2](https://github.com/888newstep/ai-agent-platform/pull/2)）。

## License

本项目基于 [Apache License 2.0](LICENSE) 开源。
