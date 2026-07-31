package com.aiagent.vectorstore;

import com.aiagent.config.AiProperties;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.milvus.MilvusEmbeddingStore;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@Primary
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "ai.vector-store", name = "type", havingValue = "milvus", matchIfMissing = true)
public class MilvusVectorStoreService implements VectorStoreService {

    private final AiProperties aiProperties;
    private MilvusEmbeddingStore embeddingStore;

    @PostConstruct
    public void init() {
        try {
            AiProperties.Milvus config = aiProperties.getVectorStore().getMilvus();
            this.embeddingStore = MilvusEmbeddingStore.builder()
                    .host(config.getHost())
                    .port(config.getPort())
                    .collectionName(config.getCollectionName())
                    .dimension(config.getDimension())
                    .build();
            log.info("Connected to Milvus at {}:{}, Collection: {}, Dimension: {}",
                    config.getHost(), config.getPort(),
                    config.getCollectionName(), config.getDimension());
        } catch (Exception e) {
            log.warn("Milvus 连接失败，向量存储不可用: {}", e.getMessage());
        }
    }

    @Override
    public void add(String id, Embedding embedding) {
        embeddingStore.add(id, embedding);
    }

    @Override
    public List<String> addAll(List<Embedding> embeddings, List<TextSegment> segments) {
        if (embeddingStore == null) {
            log.warn("Milvus 不可用，跳过向量存储");
            return List.of();
        }
        return embeddingStore.addAll(embeddings, segments);
    }

    @Override
    public List<EmbeddingMatch<TextSegment>> search(Embedding queryEmbedding, int topK, double minScore) {
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(topK)
                .minScore(minScore)
                .build();
        return embeddingStore.search(request).matches();
    }

    @Override
    public void remove(String id) {
        embeddingStore.remove(id);
    }

    @Override
    public void removeAll() {
        embeddingStore.removeAll();
    }

    @PreDestroy
    public void destroy() {
        log.info("Closing Milvus connection...");
    }
}