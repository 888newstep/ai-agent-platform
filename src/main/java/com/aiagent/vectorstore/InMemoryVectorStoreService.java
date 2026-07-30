package com.aiagent.vectorstore;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@ConditionalOnProperty(prefix = "ai.vector-store", name = "type", havingValue = "inmemory")
public class InMemoryVectorStoreService implements VectorStoreService {

    private final InMemoryEmbeddingStore<TextSegment> embeddingStore = new InMemoryEmbeddingStore<>();

    @Override
    public void add(String id, Embedding embedding) {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        embeddingStore.add(id, embedding);
        log.info("Added embedding with id: {}", id);
    }

    @Override
    public void addAll(List<Embedding> embeddings, List<TextSegment> segments) {
        embeddingStore.addAll(embeddings, segments);
        log.info("Added {} embeddings", embeddings.size());
    }

    @Override
    public List<EmbeddingMatch<TextSegment>> search(Embedding queryEmbedding, int topK, double minScore) {
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(topK)
                .minScore(minScore)
                .build();
        List<EmbeddingMatch<TextSegment>> matches = embeddingStore.search(request).matches();
        log.info("Found {} matches", matches.size());
        return matches;
    }

    @Override
    public void remove(String id) {
        // InMemoryEmbeddingStore doesn't support remove operation
        log.info("Remove operation not supported for in-memory store");
    }

    @Override
    public void removeAll() {
    }
}
