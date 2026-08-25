# RAG 检索优化留档（2026-08-25）

## 1. 留档目的

本文件记录本轮客服知识库检索优化的基线、判断、代码变更和复测边界。所有数字均来自项目内正式报告，不补造未运行的结果。

## 2. 基线来源

- 数据集：`evaluation-datasets/silver-v2/retrieval-silver.json`
- 数据类型：LLM-assisted silver-label benchmark，不是人工金标集
- 数据规模：120 条检索样本，30 个源 QA，6 类客服意图
- 困难负样本：30 条，占证据样本的 50%
- Milvus：`cs_agent.ecommerce_qa`，只读模式
- 数据集 SHA-256：`4ac31609d697815fc18ca69391db54b47275a8bb42f0c377de964b1c50a76a67`
- 基线报告：`evaluation-reports/formal-silver-v2/rag-benchmark-20260825-152848.json`
- 基线代码 Git SHA：`eb14d04f7557458f8116d532be126d8ff020a762`

### 已测结果

| Top-K | 向量 Recall | 向量 Precision | 向量 F1 | 混合 Recall | 混合 Precision | 混合 F1 |
|---:|---:|---:|---:|---:|---:|---:|
| 1 | 13.33% | 13.33% | 13.33% | 5.83% | 5.83% | 5.83% |
| 3 | 20.83% | 6.94% | 10.42% | 15.83% | 5.28% | 7.92% |
| 5 | 25.00% | 5.00% | 8.33% | 20.83% | 4.17% | 6.94% |

向量 Top-5 平均延迟为 148.43ms，混合 Top-5 平均延迟为 138.97ms。两组 `emptyResultRate=0`、`retrievalHitRate=100%`，后者只表示返回了非空结果，不表示正确文档被召回。

### 已发现的问题

1. 向量检索在本数据集上优于当前等权混合检索，生产配置却默认开启混合检索。
2. 混合检索使用向量与 BM25 等权 RRF，缺少数据驱动的路由权重。
3. BM25 中文分词包含大量低信息疑问词，可能把通用词匹配误当作业务相关性。
4. 120 条样本由 30 个源 QA 扩展而来，独立业务场景数低于表面样本数。
5. 当前每条样本通常只有一个相关 ID，未完整表达同义或部分相关文档。

## 3. 本轮已实施优化

### 3.1 默认使用已验证的向量路径

`ai.rag.enable-hybrid-search` 默认改为 `false`，并支持通过 `AI_RAG_ENABLE_HYBRID_SEARCH=true` 显式开启混合检索。这样生产默认不会继续使用当前评测中质量较低的等权混合路径。

### 3.2 混合检索改为向量优先加权 RRF

默认参数：

```text
vectorWeight=0.95
bm25Weight=0.05
rrfK=60
vectorCandidateTopK=20
bm25CandidateTopK=20
bm25CorpusMaxDocs=5000
corpusBm25Enabled=false
bm25StopwordEnabled=true
```

权重和候选池全部由 `AiProperties.Rag` 配置，并写入评测报告的 `configSnapshot`。缓存键也包含这些参数，避免调参后复用旧结果。

### 3.3 BM25 增加中文低信息词过滤

BM25 默认过滤“怎么、可以、是否、多少、请问”等低信息词，同时保留 `bm25StopwordEnabled=false` 的消融开关。业务实体、数字和中文双字词仍参与匹配。对于 `ecommerce_qa` 这类大集合，默认关闭应用内全量 BM25，改为对单次向量 Top-50 候选做轻量词法重排；小型知识库可显式开启全量语料模式。

### 3.4 BM25 索引配置变更自动失效

内存 BM25 索引现在记录停用词开关和语料上限配置。配置变化时会重建索引，避免继续使用旧分词策略。

## 4. 复测方案

复测必须使用同一份 `silver-v2` 和同一 Milvus collection，保持以下变量不变：

1. 向量基线：`hybridSearch=false`。
2. 新混合方案：`hybridSearch=true`、`0.95/0.05` 加权 RRF、中文停用词开启。
3. 消融 A：`bm25StopwordEnabled=false`。
4. 消融 B：权重 `0.60/0.40`。
5. 消融 C：权重 `0.50/0.50`。
6. 每组运行 Top-K `1,3,5`，记录 Recall、Precision、F1、平均延迟、P95、P99、空结果率。

复测前应先确认：

- Milvus TCP 可达；
- `qa_pair_id` 与数据集 `relevantDocIds` 一致；
- query 文件按 UTF-8 被 Java 正确读取；
- 查询和入库使用同一 Embedding 模型；
- 应用以 `AI_VECTOR_STORE_MODE=qa`、`MILVUS_READ_ONLY=true` 启动。

## 5. 当前不能宣称的结论

- 只能陈述当前 `silver-v2` 对照结果，不能外推为其他数据集或线上流量的必然提升。
- 不能把银标评测结果写成线上客服准确率。
- 不能把 `retrievalHitRate=100%` 写成正确召回率 100%。
- 尚未完成人工抽检，因此不能称为人工金标评测。

## 6. 优化后正式复测

- 报告：`evaluation-reports/formal-silver-v2/optimized-v4/rag-benchmark-20260825-171330.json`
- 报告 SHA-256：`0d9fe9bcb7cb4767f8b13935514c6eaf7c8f0f5e182529f80ff643356eaa35a2`
- 数据集、Milvus collection、阈值和 Top-K 与基线保持一致

| Top-K | 向量 Recall | 新混合 Recall | 向量 F1 | 新混合 F1 |
|---:|---:|---:|---:|---:|
| 1 | 14.17% | 14.17% | 14.17% | 14.17% |
| 3 | 20.83% | 20.83% | 10.42% | 10.42% |
| 5 | 25.00% | 26.67% | 8.33% | 8.89% |

Top-5 平均延迟从 137.62ms 增至 142.91ms，P95 从 188ms 降至 171ms。新方案在 Top-1/3 无质量回归，Top-5 Recall 提升 1.67 个百分点；该结论仅适用于当前银标集，不能表述为线上准确率提升。

## 7. 后续数据留档

优化后的正式 JSON 报告应放入 `evaluation-reports/formal-silver-v2/`，文件名包含运行时间，并保留：

- Git SHA；
- 数据集 SHA-256；
- Milvus collection 和只读状态；
- 所有检索配置；
- 总体和分类指标；
- 各组延迟分位数；
- 失败样本分类统计。

当前没有生成逐条失败结果，因为现有正式报告没有保存每条 query 的 Top-K 明细。后续若要做根因分析，应扩展评测输出保存脱敏后的 query、expected IDs、actual IDs、分数、路由来源和排名。
