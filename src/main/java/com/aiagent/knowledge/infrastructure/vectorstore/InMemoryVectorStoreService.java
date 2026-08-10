package com.aiagent.knowledge.infrastructure.vectorstore;

import com.aiagent.knowledge.domain.RetrievalChunk;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
@Service
@ConditionalOnProperty(prefix = "ai.vector-store", name = "type", havingValue = "inmemory")
public class InMemoryVectorStoreService implements VectorStoreService {

    private final InMemoryEmbeddingStore<TextSegment> embeddingStore = new InMemoryEmbeddingStore<>();
    private final List<RetrievalChunk> chunks = new CopyOnWriteArrayList<>();

    @Override
    public void add(String id, Embedding embedding) {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        embeddingStore.add(id, embedding);
        log.info("Added embedding with id: {}", id);
    }

    @Override
    public List<String> addAll(List<Embedding> embeddings, List<TextSegment> segments) {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < embeddings.size(); i++) {
            String id = UUID.randomUUID().toString();
            embeddingStore.add(id, embeddings.get(i), segments == null ? null : segments.get(i));
            ids.add(id);
            if (segments != null && i < segments.size() && segments.get(i) != null) {
                TextSegment segment = segments.get(i);
                chunks.add(RetrievalChunk.builder()
                        .id(id)
                        .content(segment.text())
                        .score(0.0)
                        .metadata(segment.metadata() == null ? Map.of() : segment.metadata().toMap())
                        .build());
            }
        }
        log.info("Added {} embeddings", embeddings.size());
        return ids;
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
    public List<RetrievalChunk> fetchAllChunks(int maxDocs) {
        int limit = maxDocs > 0 ? Math.min(maxDocs, chunks.size()) : chunks.size();
        return new ArrayList<>(chunks.subList(0, limit));
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
