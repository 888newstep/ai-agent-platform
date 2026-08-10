# RAG 基准评测运行手册

## 目标

本项目的评测接口已经可以输出 `sampleCount`、`recall`、`precision`、`f1`、平均延迟、P50、P95 和 P99，并按 `category` 输出分组指标。`scripts/run-rag-evaluation.ps1` 将同一数据集分别以“向量检索”和“混合检索”运行，导出两份真实报告，并调用历史对比接口生成差值。

脚本只保存服务端返回的指标，不生成或填充虚假结果。没有可用的 Milvus、Embedding 模型或已导入文档时，脚本应失败或得到低召回结果，不能把测试桩数据当成线上结论。

## 前置条件

1. 启动应用、MySQL、Redis 和 Milvus；本地可使用 `docker compose up -d app milvus redis mysql`。
2. 配置 `ADMIN_API_KEY`，并确保应用能够访问 Embedding 模型。
3. 将评测数据集放在 `AI_EVALUATION_DATASET_DIRECTORY` 目录内。
4. 数据集中的 `relevantDocIds` 必须对应已经导入 Milvus 的 chunk ID；仓库样例只是数据格式示例，不包含业务文档。

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

## 如何记录结果

基准脚本记录当前 Git commit、数据集文件名、数据集 SHA-256、文件大小和非敏感向量库配置。汇总产物只保留报告文件名与指标，不写入数据集绝对路径、导出文件绝对路径、服务地址或 API 密钥，可在脱敏检查后分享。

每次基准至少记录以下信息：

- Git commit SHA、运行时间和数据集版本。
- 数据集样本数、类别分布和 `relevantDocIds` 生成规则。
- 模型提供商、Embedding 模型、Milvus 索引参数和 `novel_id`/业务过滤条件。
- Top-K、相似度阈值、是否启用混合检索、RRF 参数。
- 每个 Top-K 的样本数、recall、precision、F1、平均延迟、P50、P95、P99，以及各 category 的分组指标。
- 向量-only 与 hybrid 的指标差值，以及失败样本归因。

`evaluation-reports/` 默认被 Git 忽略。提交 GitHub 前只提交脱敏后的汇总表或 Markdown 摘要，不提交用户问题、文档原文、绝对路径和 API 响应中的敏感信息。

## 指标解释

- `recall@k`：标注相关 chunk 中被 Top-K 召回的比例。
- `precision@k`：Top-K 结果中相关 chunk 的比例。
- `f1@k`：recall 与 precision 的调和平均。
- `avgLatency`、`p50Latency`、`p99Latency`：评测请求的检索耗时统计，不能直接等同于完整端到端回答延迟。
- 只有数据集、Top-K 和过滤条件一致时，历史报告差值才具有可比性。

## 当前限制

- 样例数据集中的文档 ID 是占位 ID，不能直接代表项目实际召回率。
- 没有真实文档和 Milvus 时，不应在简历或 README 中填写评测提升百分比。
- 该脚本比较的是两次真实 API 调用；它不替代压测工具，也不提供 QPS/P99 端到端容量结论。

## 已有 QA collection 模式

如果 Milvus 中已经存在本项目导入的 `ecommerce_qa` collection，需要显式启用 V2 QA schema 适配器。该 collection 的字段是 `question`、`answer`、`qa_text`、`qa_pair_id`、`embedding`，不能直接使用默认的 LangChain4j `text/metadata/vector` schema。

```powershell
$env:AI_VECTOR_STORE_MODE = "qa"
$env:MILVUS_COLLECTION_NAME = "ecommerce_qa"
$env:MILVUS_READ_ONLY = "true"
$env:MILVUS_CONNECTION_TIMEOUT_MS = "60000"
```

`qa` 模式只读时不会向已有业务 collection 写入数据；数据导入仍由电商 QA 导入服务负责。默认 `langchain` 模式保持通用文档链路不变。

## 2026-08-10 云端 Milvus 结果（脱敏）

本次连接云服务器上的 `ecommerce_qa` collection，应用使用 QA schema 适配器和只读模式，基于 20 条本地私有 query 构造 smoke 数据集，并以真实 `qa_pair_id` 作为相关 ID，阈值为 `0.60`，`Top-K=1,3,5`。结果如下：

| 配置 | Recall@1 | Recall@3 | Recall@5 | F1@3 | Avg latency@3 | P99@3 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 向量基线 | 33.33% | 98.33% | 100.00% | 98.33% | 162.05 ms | 265 ms |
| 混合检索 | 21.67% | 41.67% | 53.33% | 41.67% | 178.65 ms | 666 ms |

这次结果不代表“混合检索无价值”，但说明当前实现和数据集之间存在需要继续分析的回归：数据集的相关 ID 是根据向量检索结果自动构造的，天然偏向向量基线，不能用于证明业务场景中的最终优劣。下一步应使用独立人工标注的 `question/relevantDocIds/category` 数据集，或重新设计不依赖单一路由结果的标注规则，再调节 BM25 候选池、RRF 权重和中文分词策略。该结果不能直接写入简历为业务提升数据；原始 query、ID 列表、API 响应和评测 JSON 均保持本地私有。
