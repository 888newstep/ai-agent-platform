package com.aiagent.rag.application;

import com.aiagent.knowledge.domain.RetrievalChunk;

import java.util.List;

public interface EvidenceReranker {
    List<RetrievalChunk> rerank(String query, List<RetrievalChunk> chunks);
}
