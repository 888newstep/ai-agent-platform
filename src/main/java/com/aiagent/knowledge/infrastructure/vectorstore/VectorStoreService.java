package com.aiagent.knowledge.infrastructure.vectorstore;

import com.aiagent.knowledge.domain.RetrievalChunk;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;

import java.util.List;

public interface VectorStoreService {
    default boolean isAvailable() {
        return true;
    }

    default boolean isWriteAvailable() {
        return true;
    }

    void add(String id, Embedding embedding);
    List<String> addAll(List<Embedding> embeddings, List<TextSegment> segments);
    List<EmbeddingMatch<TextSegment>> search(Embedding queryEmbedding, int topK, double minScore);

    /**
     * 在精选 FAQ 库中检索（级联检索第一层）。
     * 默认实现不检索 FAQ 库，由支持 FAQ collection 的向量服务覆盖。
     */
    default List<EmbeddingMatch<TextSegment>> searchFaq(Embedding queryEmbedding, int topK, double minScore) {
        return List.of();
    }

    List<RetrievalChunk> fetchAllChunks(int maxDocs);
    void remove(String id);
    void removeAll();
}
