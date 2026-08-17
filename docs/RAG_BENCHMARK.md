# RAG 基准评测运行手册

## 目标

本项目的评测接口已经可以输出 `sampleCount`、`recall`、`precision`、`f1`、平均延迟、P50、P95、P99，以及 `emptyResultCount`、`emptyResultRate`、`retrievalHitRate`，并按 `category` 输出分组指标。`scripts/run-rag-evaluation.ps1` 将同一数据集分别以“向量检索”和“混合检索”运行，导出两份真实报告，并调用历史对比接口生成差值。

脚本只保存服务端返回的指标，不生成或填充虚假结果。没有可用的 Milvus、Embedding 模型或已导入文档时，脚本应失败或得到低召回结果，不能把测试桩数据当成线上结论。

## 前置条件

1. 启动应用、MySQL、Redis 和 Milvus；本地可使用 `docker compose up -d app milvus redis mysql`。
2. 混合部署时，先运行 `scripts/check-infrastructure.ps1` 检查本地 MySQL/Redis 与云端 Milvus；RabbitMQ 只有显式提供 `RABBITMQ_HOST` 时才会检查，且不属于本项目运行链路。
3. 配置 `ADMIN_API_KEY`，并确保应用能够访问 Embedding 模型。
4. 将评测数据集放在 `AI_EVALUATION_DATASET_DIRECTORY` 目录内。
5. 数据集中的 `relevantDocIds` 必须对应已导入 Milvus 的目标 ID：通用文档模式使用 chunk ID，QA 模式使用 `qa_pair_id`；仓库样例只是数据格式示例，不包含业务文档。

混合部署预检示例：

```powershell
$env:MILVUS_HOST = "your-cloud-milvus-host"
$env:RABBITMQ_HOST = "your-cloud-rabbitmq-host"
.\scripts\check-infrastructure.ps1
```

该脚本只验证 TCP 和非敏感目标配置，不验证 collection 是否存在、是否有数据，也不会替代后续应用启动与只读检索验证。

Docker 镜像会复制 `examples/evaluation-datasets/` 到 `/app/examples/evaluation-datasets/`，因此默认样例路径在容器内也有效。真实数据不要复制进镜像，使用本地私有目录或受控挂载。

## 运行基准

PowerShell 示例：

```powershell
$env:ADMIN_API_KEY = "replace-with-your-admin-key"
.\scripts\run-rag-evaluation.ps1
```

脚本默认参数：

- 数据集：`examples/evaluation-datasets/rag-sample.json`
- Top-K：`1,3,5`
- 相似度阈值：`0.60`
- Baseline：`hybridSearch=false`
- Candidate：`hybridSearch=true`
- 数据集类型：通过 `-DatasetKind` 显式标记为 `sample`、`smoke` 或 `independent-human-labeled`
- 本地运行产物：`evaluation-reports/rag-benchmark-*.json`

使用真实数据时，先配置目录，再传入目录内的文件路径：

```powershell
$env:AI_EVALUATION_DATASET_DIRECTORY = "C:\private\rag-datasets"
.\scripts\run-rag-evaluation.ps1 `
  -DatasetPath "C:\private\rag-datasets\customer-faq.json" `
  -TopKs "1,3,5,10" `
  -SimilarityThreshold 0.65
```

如果 PowerShell 执行策略阻止脚本，可只对当前进程放行：

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
```

如果当前 PowerShell 进程没有继承应用启动时的向量库环境变量，可以用显式参数补全本次报告的运行元数据：

```powershell
.\scripts\run-rag-evaluation.ps1 `
  -VectorStoreMode "qa" `
  -MilvusCollection "ecommerce_qa" `
  -MilvusReadOnly "true"
```

这些参数只写入本次本地汇总报告，不会修改已经运行的应用配置；应用仍必须在启动前通过环境变量配置正确的向量库模式、collection 和只读策略。

### 独立人工标注集流程

正式基准必须使用独立人工标注的 `question/relevantDocIds/category` 数据集，不能根据某一路由的 Top-K 结果自动生成 `relevantDocIds`。建议按以下流程执行：

1. 从真实业务问题或脱敏 FAQ 中抽取问题，删除用户隐私、订单号和绝对路径，并冻结数据集版本。
2. 在目标 collection 中人工确认所有相关文档 ID；QA 模式使用 `qa_pair_id`，通用文档模式使用 chunk ID。
3. 使用固定类别集合标注 `category`；对关键样本安排第二名标注者复核，冲突由人工裁决。
4. 先运行 `scripts/validate-rag-dataset.ps1` 做结构检查，再运行双配置评测脚本。
5. 只有通过人工复核、ID 存在性检查和数据集版本记录后，才将报告标记为正式基准。

示例：

```powershell
.\scripts\validate-rag-dataset.ps1 `
  -DatasetPath 'C:\private\rag-datasets\customer-faq.json' `
  -DatasetKind independent-human-labeled `
  -MinCases 30 `
  -MinCategories 3 `
  -RequireCategory

.\scripts\run-rag-evaluation.ps1 `
  -DatasetPath 'C:\private\rag-datasets\customer-faq.json' `
  -DatasetKind independent-human-labeled `
  -VectorStoreMode qa `
  -MilvusCollection ecommerce_qa `
  -MilvusReadOnly true
```

校验脚本不会读取或上传数据内容，也不会查询 Milvus；它只能保证输入结构满足评测服务约定。`datasetKind=sample/smoke` 的报告只能用于链路诊断和回归观察，不能直接作为简历中的业务提升数据。

## 如何记录结果

基准脚本记录当前 Git commit、数据集文件名、数据集 SHA-256、文件大小和非敏感向量库配置。汇总产物只保留报告文件名与指标，不写入数据集绝对路径、导出文件绝对路径、服务地址或 API 密钥，可在脱敏检查后分享。

每次基准至少记录以下信息：

- Git commit SHA、运行时间和数据集版本。
- 数据集样本数、类别分布和 `relevantDocIds` 生成规则。
- 模型提供商、Embedding 模型、Milvus 索引参数和 `novel_id`/业务过滤条件。
- Top-K、相似度阈值、是否启用混合检索、RRF 参数。
- 每个 Top-K 的样本数、recall、precision、F1、平均延迟、P50、P95、P99、空结果率和检索命中率，以及各 category 的分组指标。
- 向量-only 与 hybrid 的指标差值，以及失败样本归因。

## 2026-08-12 QA smoke（非正式基准）

已在只读模式下连接云端 `cs_agent.ecommerce_qa`，运行元数据为 `AI_VECTOR_STORE_MODE=qa`、`MILVUS_READ_ONLY=true`，使用本地私有 20 条样本、相似度阈值 `0.60` 和 `Top-K=1,3,5`。本次结果仅用于验证链路、数据字段和评测脚本，不作为业务效果结论：

| 配置 | Recall@1 | Recall@3 | Recall@5 | F1@3 | Avg latency@3 | P99@3 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 向量基线 | 20.00% | 50.00% | 65.00% | 25.00% | 160.35 ms | 241 ms |
| 混合检索 | 25.00% | 60.00% | 70.00% | 30.00% | 279.30 ms | 382 ms |

两组配置的 `retrievalHitRate` 均为 `100%`，`emptyResultRate` 均为 `0%`。样本的 `relevantDocIds` 使用真实 `qa_pair_id`，但标注样本由 collection 记录自动抽取，仍然存在数据构造偏差，不能据此宣称混合检索带来业务提升。正式结论仍需要独立人工标注的 `question/relevantDocIds/category` 数据集；原始样本、ID 列表和报告均保持本地私有。

`evaluation-reports/` 默认被 Git 忽略。提交 GitHub 前只提交脱敏后的汇总表或 Markdown 摘要，不提交用户问题、文档原文、绝对路径和 API 响应中的敏感信息。

## 指标解释

- `recall@k`：标注相关 chunk 中被 Top-K 召回的比例。
- `precision@k`：Top-K 结果中相关 chunk 的比例。
- `f1@k`：recall 与 precision 的调和平均。
- `avgLatency`、`p50Latency`、`p99Latency`：评测请求的检索耗时统计，不能直接等同于完整端到端回答延迟。
- `emptyResultRate`：没有返回任何候选结果的样本比例；接近 100% 通常意味着 collection 为空、过滤条件不匹配或 Milvus/Embedding 链路不可用。
- `retrievalHitRate`：至少返回一个候选结果的样本比例；它是判断当前报告是否具备检索证据的快速信号，不等同于召回率。
- 只有数据集、Top-K 和过滤条件一致时，历史报告差值才具有可比性。

## 当前限制

- 截至 2026-08-12 最新工作站复核：本地 Win11 的 MySQL `localhost:3306`、Redis `localhost:6379` 可达；云端 Milvus `19530` 与 RabbitMQ `5672` 均已通过 TCP 预检。只读清单显示 `cs_agent.ai_agent_documents` 存在但 `row_count=0`，而 QA collection `ecommerce_qa` 有 `225034` 条记录，因此可以在 `AI_VECTOR_STORE_MODE=qa` 下进行只读 smoke 检索；正式效果结论仍需独立人工标注数据集。RabbitMQ 当前未接入 `newagent` 运行链路。

- 样例数据集中的文档 ID 是占位 ID，不能直接代表项目实际召回率。
- 没有真实文档和 Milvus 时，不应在简历或 README 中填写评测提升百分比。
- 该脚本比较的是两次真实 API 调用；它不替代压测工具，也不提供 QPS/P99 端到端容量结论。

## 已有 QA collection 模式

如果 Milvus 中已经存在本项目导入的 QA collection（例如 `ecommerce_qa` 或当前配置的 `cs_agent.ai_agent_documents`），需要显式启用 V2 QA schema 适配器。当前实际复核到的两个 collection 都使用 `question`、`answer`、`qa_text`、`qa_pair_id`、`category`、`embedding` 等 QA schema 字段，不能直接使用默认的 LangChain4j `text/metadata/vector` schema；其中 `cs_agent.ai_agent_documents` 当前 `row_count=0`，`ecommerce_qa` 当前有 `225034` 条记录。云端的 `novel_*` 与 `knowledge_base` collection 属于其他数据链路，不纳入本项目评测。

```powershell
$env:AI_VECTOR_STORE_MODE = "qa"
$env:MILVUS_DATABASE_NAME = "cs_agent"
$env:MILVUS_COLLECTION_NAME = "ai_agent_documents"
$env:MILVUS_READ_ONLY = "true"
$env:MILVUS_CONNECTION_TIMEOUT_MS = "60000"
```

`qa` 模式只读时不会向已有业务 collection 写入数据；数据导入仍由对应的 QA 导入服务负责。若实际使用 `ecommerce_qa`，只需替换 `MILVUS_COLLECTION_NAME`。默认 `langchain` 模式保持通用文档链路不变。

## 2026-08-10 历史云端 Milvus 结果（当前不可复核）

2026-08-10 当时连接云服务器上的 `ecommerce_qa` collection，应用使用 QA schema 适配器和只读模式，基于 20 条本地私有 query 构造 smoke 数据集，并以真实 `qa_pair_id` 作为相关 ID，阈值为 `0.60`，`Top-K=1,3,5`。结果如下：

| 配置 | Recall@1 | Recall@3 | Recall@5 | F1@3 | Avg latency@3 | P99@3 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 向量基线 | 33.33% | 98.33% | 100.00% | 98.33% | 162.05 ms | 265 ms |
| 混合检索 | 21.67% | 41.67% | 53.33% | 41.67% | 178.65 ms | 666 ms |

历史记录：2026-08-11 曾有一次云端 Milvus 可达时的只读复核，随后一次 10 秒 TCP 复核出现超时；截至 2026-08-12，最新基础设施预检已恢复可达。当时的 collection 列表未发现 `ecommerce_qa`，且 `cs_agent.ai_agent_documents` 的 `row_count=0`。下面的历史结果仍不能由当前环境重新验证，保留它仅用于记录当时的实验结论，不作为当前基线。

这次结果不代表“混合检索无价值”，但说明当前实现和数据集之间存在需要继续分析的回归：数据集的相关 ID 是根据向量检索结果自动构造的，天然偏向向量基线，不能用于证明业务场景中的最终优劣。下一步应使用独立人工标注的 `question/relevantDocIds/category` 数据集，或重新设计不依赖单一路由结果的标注规则，再调节 BM25 候选池、RRF 权重和中文分词策略。该结果不能直接写入简历为业务提升数据；原始 query、ID 列表、API 响应和评测 JSON 均保持本地私有。

### 2026-08-10 后续修复：BM25 独立词法召回路

针对上述回归，已修正 MultiRecallService 的混合检索实现：

- 此前 BM25 只对向量 Top-N（N=50）候选池做重排，无法召回向量漏掉的文档，本质是“稠密召回 + 自身重排”，违背多路召回设计。
- 现在 BM25 改为对全量语料建立可缓存索引（TTL=5min，上限 5000 chunk），作为独立词法召回路，再与向量 Top-20 经 RRF 融合；语料不可用时自动退化为候选池重排。
- 中文分词由单字改为叠加 bigram，减少单字过匹配，提升词法精确度。
- 注意：本次 smoke 数据集的相关 ID 由向量结果自动生成、天然偏向向量，仅凭该数据集仍无法证明混合检索在业务上更优；需用独立人工标注数据集重新评测后再调参（候选池、RRF 权重）。
