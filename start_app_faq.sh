#!/bin/bash
# 启动 newagent（QA 模式 + FAQ 级联检索）
cd /c/Users/xiaohongfu/IdeaProjects/newagent || exit 1

# 1. 导入 .env 全部环境变量（MySQL/Redis/Milvus/API Key 等）
set -a
source .env
set +a

# 2. QA 模式 + 检索配置
export AI_VECTOR_STORE_MODE=qa
export MILVUS_COLLECTION_NAME=ecommerce_qa
export MILVUS_CONNECTION_TIMEOUT_MS=15000
export AI_EVALUATION_DATASET_DIRECTORY=./evaluation-datasets

# 3. FAQ 级联检索开关
export MILVUS_FAQ_FIRST_ENABLED=true
export MILVUS_FAQ_COLLECTION_NAME=ecommerce_faq
export MILVUS_FAQ_HIT_THRESHOLD=${FAQ_HIT_THRESHOLD:-0.75}
export MILVUS_FAQ_TOP_K=3

# 4. 混合检索扩容（扩大候选池 + 增加 BM25 语料覆盖）
export AI_RAG_HYBRID_VECTOR_CANDIDATE_TOP_K=200
export AI_RAG_HYBRID_BM25_CANDIDATE_TOP_K=200
export AI_RAG_HYBRID_BM25_CORPUS_MAX_DOCS=20000
export AI_RAG_HYBRID_VECTOR_WEIGHT=0.90
export AI_RAG_HYBRID_BM25_WEIGHT=0.10
export AI_RAG_HYBRID_RRF_K=60

# 5. Cross-Encoder 重排（候选池上重排，识别语义相关）
export AI_RAG_HYBRID_CROSS_ENCODER_ENABLED=true
export AI_RAG_HYBRID_RERANK_CANDIDATE_TOP_K=200
export AI_RAG_HYBRID_RERANK_FAIL_OPEN=true

exec java -jar target/ai-agent-platform-1.0.0.jar