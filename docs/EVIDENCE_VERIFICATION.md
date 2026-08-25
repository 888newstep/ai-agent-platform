# 证据验证与 Cross-Encoder 上线手册

## 验证链路

客服回答依次经过：多路召回、语义重排、证据充分性验证、MySQL 有效性校验、答案生成、答案支持性验证。任一步失败都不会返回模型生成答案，而是转人工或返回依赖不可用。

证据充分性同时要求：

1. reranker 分数达到对应 provider 门槛；
2. 业务关键词覆盖率达到配置门槛；
3. 问题中的阿拉伯数字能在证据中找到；
4. 客服入口最终验证等级达到 `HIGH`。

答案支持性验证会拆分回答声明和证据子句，检查语义支持比例、平均语义分数、数字事实和基础否定极性。它不能完全替代专业 NLI 模型，因此生产评测必须包含反事实、否定、条件限制和数字时效样本。

## Reranker 模式

### Embedding

默认模式，不增加新服务依赖，适合本地开发和回归测试：

```properties
AI_RAG_RERANK_PROVIDER=embedding
AI_RAG_SEMANTIC_RERANK_MIN_SCORE=0.62
```

优点是部署简单、一次批量 embedding 即可完成；缺点是相关性判断弱于 cross-encoder，尤其难区分同词反义、条件缺失和仅主题相关的证据。

### Cross-Encoder

项目使用标准 JSON rerank 协议：

```json
{
  "model": "BAAI/bge-reranker-v2-m3",
  "query": "退款多久到账？",
  "documents": ["退款审核通过后原路返回，到账时间以支付渠道为准。"],
  "top_n": 1,
  "return_documents": false
}
```

响应必须为：

```json
{
  "results": [
    {"index": 0, "relevance_score": 0.91}
  ]
}
```

推荐配置：

```properties
AI_RAG_RERANK_PROVIDER=cross-encoder
AI_RAG_RERANK_ENDPOINT=https://api.siliconflow.cn/v1/rerank
AI_RAG_RERANK_API_KEY=replace-me
AI_RAG_RERANK_MODEL=BAAI/bge-reranker-v2-m3
AI_RAG_RERANK_MIN_SCORE=0.55
AI_RAG_RERANK_TIMEOUT_MS=10000
AI_RAG_RERANK_MAX_DOCUMENT_CHARACTERS=4000
AI_RAG_RERANK_FALLBACK_TO_EMBEDDING=false
```

不要直接沿用 embedding 阈值。不同 reranker 的分数分布不同，必须使用当前模型和真实业务标注集重新校准。

## 人工标注规范

每条样本至少包含：`question`、`keywords`、`evidence`、`expectedSupported`、`category` 和 `note`。冻结回归可以增加 `fixtureSemanticScore`；真实模型评测会忽略该字段。

数据集应至少覆盖：

- 同词但没有回答问题；
- 正反事实冲突；
- 缺失条件，例如“发货前”与“任何时候”；
- 缺失或新增价格、天数、比例、数量；
- 多条证据才能完整支持；
- 过期政策和相互冲突的政策；
- OCR 错字、表格拆行和文档切片边界。

标注人员只判断“给定证据是否足以安全回答”，不能根据常识补全。争议样本应由第二位标注者复核，并记录最终裁决原因。

## 运行评测

固定回归：

```powershell
Invoke-RestMethod -Method Post `
  -Headers @{ "X-Admin-Api-Key" = $env:ADMIN_API_KEY } `
  -Uri "http://localhost:8081/api/v1/agent/evaluate/evidence?datasetPath=examples/evaluation-datasets/evidence-verification-sample.json&liveRerank=false"
```

真实 reranker：

```powershell
Invoke-RestMethod -Method Post `
  -Headers @{ "X-Admin-Api-Key" = $env:ADMIN_API_KEY } `
  -Uri "http://localhost:8081/api/v1/agent/evaluate/evidence?datasetPath=C:/private/rag-datasets/evidence.json&liveRerank=true"
```

文件必须位于 `AI_EVALUATION_DATASET_DIRECTORY` 下，避免管理员接口读取任意服务器文件。

## 上线门槛

建议至少满足：

- 独立人工标注样本不少于 100 条；
- 困难负样本不少于 40%；
- `falsePositiveRate <= 2%`；
- 退款、赔付、价格、时效类样本不得出现误放；
- P95 rerank 延迟满足客服接口目标；
- reranker 故障演练确认默认失败关闭；
- 观察一周 shadow 流量后再允许影响正式回答。

阈值选择优先降低 false positive，而不是单纯追求 Accuracy。客服知识库中，错误承诺通常比转人工成本更高。
