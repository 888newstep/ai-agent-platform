# LLM-Assisted RAG Evaluation Data

## Scope

This pipeline builds a reproducible silver-label evaluation set from the real customer-service QA table. It is intended for regression testing, reranker ablation, threshold calibration, and interview evidence. It does not replace an independently reviewed business gold set.

The source table contains raw conversations rather than approved policy documents. The generator therefore rejects incomplete, risky, context-dependent, or privacy-sensitive records before creating evaluation cases.

## Pipeline

`scripts/generate-rag-silver-dataset.ps1` performs the following steps:

1. Read active QA records from MySQL without modifying them.
2. Apply deterministic length, generic-answer, and privacy filters.
3. Ask one model to classify each source, create four query variants, and construct an adversarial unsupported evidence item.
4. Ask two different model providers to independently validate source quality, intent preservation, category, privacy, and negative-evidence correctness.
5. Keep only unanimous decisions that also pass local rules.
6. Balance the selected records across customer-service intent categories.
7. Write retrieval, evidence, audit, and manifest files to the ignored private dataset directory.

The default 30 accepted source records produce:

- 120 retrieval cases: four query variants for each real `qa_pair_id`.
- 60 evidence cases: one supported and one adversarial unsupported case per source.
- 50% hard negatives in the evidence set.

## Generate

The default providers require `DEEPSEEK_API_KEY`, `DOUBAO_API_KEY`, and `QWEN3_FLASH_API_KEY`. MySQL settings are read from process environment variables or `.env`.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/generate-rag-silver-dataset.ps1 `
  -SourceCount 30 `
  -CandidateMultiplier 4 `
  -OutputDirectory C:\private\rag-datasets\silver-v1
```

The generator model must not also be one of the judge models. Provider failures and malformed responses stop the run; the pipeline does not silently reduce the consensus requirement.

If model calls completed but final selection needs to be repeated after changing local uniqueness or balancing rules, reuse the private audit file without spending model quota again:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/finalize-rag-silver-dataset.ps1 `
  -AuditPath C:\private\rag-datasets\silver-v1\generation-audit.json `
  -SourceCount 30 `
  -MinCategories 6
```

## Validate

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/validate-rag-dataset.ps1 `
  -DatasetPath C:\private\rag-datasets\silver-v1\retrieval-silver.json `
  -DatasetKind llm-assisted-silver `
  -MinCases 100 `
  -MinCategories 6 `
  -RequireCategory

powershell -NoProfile -ExecutionPolicy Bypass -File scripts/validate-evidence-dataset.ps1 `
  -DatasetPath C:\private\rag-datasets\silver-v1\evidence-silver.json `
  -DatasetKind llm-assisted-silver `
  -MinCases 60 `
  -MinNegativeRatio 0.4 `
  -MinCategories 6
```

Validate that every source ID exists in the read-only QA collection:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/validate-rag-milvus-ids.ps1 `
  -DatasetPath C:\private\rag-datasets\silver-v1\retrieval-silver.json `
  -MilvusHost $env:MILVUS_HOST `
  -Database cs_agent `
  -Collection ecommerce_qa `
  -OutputPath C:\private\rag-datasets\silver-v1\milvus-id-validation.json
```

## Evaluate

Retrieval evaluation already disables the RAG result cache. Run the evidence benchmark with `liveRerank=true`; `liveRerank=false` uses fixture scores and is not an embedding-provider baseline.

For an embedding versus cross-encoder comparison, run two live evidence evaluations with the same frozen dataset:

1. `AI_RAG_RERANK_PROVIDER=embedding`
2. `AI_RAG_RERANK_PROVIDER=cross-encoder`

Record the provider, model, threshold, dataset SHA-256, Git revision, and latency for both runs.

## Manual Review Boundary

The manifest always records `manualReview.completed=false`. Review at least 20 cases or 20% of selected sources, whichever is larger, prioritizing refunds, compensation, price, delivery time, numeric mutations, and judge explanations that express uncertainty.

Before review, describe results as an "LLM-assisted silver-label benchmark." Do not describe them as independent human labels, production accuracy, or an approved policy benchmark.
