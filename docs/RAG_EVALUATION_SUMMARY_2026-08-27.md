# RAG 检索评测摘要（2026-08-27）

> 本文件与 `docs/evaluation-snapshots/` 下的评测快照 JSON 一一对应，用于简历数据可核验。
> 评测数据集存放于 `evaluation-datasets/silver-v2/`（retrieval-silver.json 系列，120 条客服问答场景）。

## 一、关键指标对比

| 指标 | 基线（raw 混合检索） | FAQ-first + multi-gold |
|------|---------------------|------------------------|
| 数据集 | `retrieval-silver.json`（单金标 #b3f3c5） | `retrieval-silver-mg.json`（多金标 #b0630f） |
| 评测时间 | 2026-08-26 19:12 | 2026-08-27 10:39 |
| Recall@1 | **47.50%** | **98.33%** |
| Recall@3 | 60.00% | 100.00% |
| Recall@5 | 62.50% | 100.00% |
| Hit 率 | 100% | 100% |
| 空结果率 | 0% | 0% |
| p50 延迟 | 139ms | **129ms** |
| p95 延迟 | 2,672ms | **181ms** |
| p99 延迟 | 3,342ms | 435ms |

## 二、六类别 Recall@1（FAQ-first + multi-gold，120 条）

| 类别 | 样本数 | Recall@1 | p95 延迟 |
|------|-------|---------|---------|
| shipping_logistics（物流） | 24 | **91.67%**（最低） | 245ms |
| promotion_price（促销价） | 24 | 100% | 254ms |
| product_specification（产品规格） | 24 | 100% | — |
| after_sales_quality（售后质量） | 24 | 100% | — |
| refund_return（退换货） | 12 | 100% | — |
| order_payment（订单支付） | 12 | 100% | — |

## 三、评测配置（FAQ-first 轮）

```json
{
  "topK": 5,
  "similarityThreshold": 0.6,
  "hybridSearch": true,
  "hybridVectorWeight": 0.9,
  "hybridBm25Weight": 0.1,
  "hybridVectorCandidateTopK": 200,
  "hybridCrossEncoderEnabled": true,
  "hybridRerankCandidateTopK": 200,
  "hybridRerankFailOpen": true,
  "bm25StopwordEnabled": true,
  "evaluationRetrievalStrategy": "single-max-top-k"
}
```

## 四、口径说明（务必阅读，避免误读数字）

1. **数据集差异**：基线与最终轮数据集不同。基线为单金标（每条 1 个相关文档），最终轮为 **multi-gold 多金标判定**（每条多个相关文档，命中任一即算 hit）——判定口径变宽是 Recall@1 从 47.5% 到 98.3% 的因素之一。
2. **FAQ 优先级联直出**：链路先查 FAQ 标准问答库（向量阈值 0.6+），命中直接返回（118/120），未命中回退 raw 混合检索兜底。FAQ 直出显著降低尾部延迟（p95 2,672ms→181ms）。
3. **raw 混合检索真实能力**：未经 FAQ 直出、单金标数据集下，Recall@1 = 47.5%（raw 基线快照保留，见 `rag-evaluation-baseline-20260826-1912-raw-hybrid-r1-47.5.json`）。
4. **chunk 口径**：`chunkSize=500/chunkOverlap=50` 为 snapshot-only，未重新灌库（`chunkingComparable=false`），重灌后数字可能变化。
5. **复现方式**：`scripts/run-rag-evaluation.ps1`，配置 `AI_EVALUATION_DATASET_DIRECTORY` 指向 `evaluation-datasets/silver-v2/`、`MILVUS_READ_ONLY=true`。

## 五、结论

- **工程收益**：FAQ 优先级联直出使端到端检索延迟 p95 从 2.7s 降至 181ms，且不牺牲召回（原始 raw 链路仍有 47.5% 基线留存对照）。
- **召回提升**：47.5%（raw 单金标）→ 98.3%（FAQ 直出 + multi-gold 判定），提升由链路与判定口径共同贡献，两者均有快照可复现。
- **诚实边界**：98.3% 对应「FAQ 域内直出命中」口径；通用 raw 检索能力以 47.5% 基线为准，不混淆两套数字。