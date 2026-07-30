package com.aiagent.vectorstore;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;

import java.util.List;

public interface VectorStoreService {
    void add(String id, Embedding embedding);
    void addAll(List<Embedding> embeddings, List<TextSegment> segments);
    List<EmbeddingMatch<TextSegment>> search(Embedding queryEmbedding, int topK, double minScore);
    void remove(String id);
    void removeAll();
}
