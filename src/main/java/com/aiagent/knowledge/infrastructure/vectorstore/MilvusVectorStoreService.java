package com.aiagent.knowledge.infrastructure.vectorstore;

import com.aiagent.infrastructure.config.AiProperties;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.milvus.MilvusEmbeddingStore;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Service
@Primary
@RequiredArgsConstructor
@ConditionalOnExpression("'${ai.vector-store.type:milvus}' == 'milvus' and '${ai.vector-store.mode:langchain}' == 'langchain'")
public class MilvusVectorStoreService implements VectorStoreService {

    private final AiProperties aiProperties;
    private final InMemoryVectorStoreService fallbackStore = new InMemoryVectorStoreService();
    private MilvusEmbeddingStore embeddingStore;

    @PostConstruct
    public void init() {
        AiProperties.Milvus config = aiProperties.getVectorStore().getMilvus();
        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "milvus-vectorstore-init");
            thread.setDaemon(true);
            return thread;
        });

        try {
            Future<MilvusEmbeddingStore> future = executor.submit(() -> MilvusEmbeddingStore.builder()
                    .host(config.getHost())
                    .port(config.getPort())
                    .collectionName(config.getCollectionName())
                    .dimension(config.getDimension())
                    .build());

            this.embeddingStore = future.get(config.getConnectionTimeoutMs(), TimeUnit.MILLISECONDS);
            log.info("Connected to Milvus at {}:{}, Collection: {}, Dimension: {}",
                    config.getHost(), config.getPort(), config.getCollectionName(), config.getDimension());
        } catch (TimeoutException e) {
            log.warn("Milvus ????({}ms)??????????", config.getConnectionTimeoutMs());
        } catch (Exception e) {
            log.warn("Milvus ??????????????: {}", extractMessage(e));
        } finally {
            executor.shutdownNow();
        }
    }

    @Override
    public void add(String id, Embedding embedding) {
        if (embeddingStore != null) {
            embeddingStore.add(id, embedding);
            return;
        }
        fallbackStore.add(id, embedding);
    }

    @Override
    public List<String> addAll(List<Embedding> embeddings, List<TextSegment> segments) {
        if (embeddingStore != null) {
            return embeddingStore.addAll(embeddings, segments);
        }
        return fallbackStore.addAll(embeddings, segments);
    }

    @Override
    public List<EmbeddingMatch<TextSegment>> search(Embedding queryEmbedding, int topK, double minScore) {
        if (embeddingStore != null) {
            EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .maxResults(topK)
                    .minScore(minScore)
                    .build();
            return embeddingStore.search(request).matches();
        }
        return fallbackStore.search(queryEmbedding, topK, minScore);
    }

    @Override
    public void remove(String id) {
        if (embeddingStore != null) {
            embeddingStore.remove(id);
            return;
        }
        fallbackStore.remove(id);
    }

    @Override
    public void removeAll() {
        if (embeddingStore != null) {
            embeddingStore.removeAll();
            return;
        }
        fallbackStore.removeAll();
    }

    @PreDestroy
    public void destroy() {
        log.info("Closing vector store...");
    }

    private String extractMessage(Exception exception) {
        Throwable cause = exception.getCause();
        return cause != null && cause.getMessage() != null ? cause.getMessage() : exception.getMessage();
    }
}
