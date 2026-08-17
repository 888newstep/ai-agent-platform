package com.aiagent.knowledge.infrastructure.vectorstore;

import com.aiagent.infrastructure.config.AiProperties;
import com.aiagent.knowledge.domain.RetrievalChunk;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.milvus.MilvusEmbeddingStore;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Service
@Primary
@ConditionalOnProperty(prefix = "ai.vector-store", name = "type", havingValue = "milvus", matchIfMissing = true)
@ConditionalOnProperty(prefix = "ai.vector-store", name = "mode", havingValue = "langchain", matchIfMissing = true)
public class MilvusVectorStoreService implements VectorStoreService {

    private final AiProperties aiProperties;
    private final MilvusClientV2 milvusClient;
    private final InMemoryVectorStoreService fallbackStore = new InMemoryVectorStoreService();
    private MilvusEmbeddingStore embeddingStore;

    public MilvusVectorStoreService(AiProperties aiProperties, Optional<MilvusClientV2> milvusClient) {
        this.aiProperties = aiProperties;
        this.milvusClient = milvusClient.orElse(null);
    }

    @PostConstruct
    public void init() {
        AiProperties.Milvus config = aiProperties.getVectorStore().getMilvus();
        if (config.isReadOnly() && !collectionExists(config)) {
            log.warn("Milvus collection [{}] is unavailable in read-only mode; disabling vector search",
                    config.getCollectionName());
            return;
        }
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
                    .databaseName(config.getDatabaseName())
                    .build());

            this.embeddingStore = future.get(config.getConnectionTimeoutMs(), TimeUnit.MILLISECONDS);
            log.info("Connected to Milvus at {}:{}, Collection: {}, Dimension: {}",
                    config.getHost(), config.getPort(), config.getCollectionName(), config.getDimension());
        } catch (TimeoutException e) {
            log.warn("Milvus connection timed out ({}ms); {}", config.getConnectionTimeoutMs(), fallbackMode());
        } catch (Exception e) {
            log.warn("Milvus connection failed; {}: {}", fallbackMode(), extractMessage(e));
        } finally {
            executor.shutdownNow();
        }
    }

    @Override
    public void add(String id, Embedding embedding) {
        ensureWritable();
        if (embeddingStore != null) {
            embeddingStore.add(id, embedding);
            return;
        }
        fallbackStore.add(id, embedding);
    }

    @Override
    public List<String> addAll(List<Embedding> embeddings, List<TextSegment> segments) {
        ensureWritable();
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
        return readOnly() ? List.of() : fallbackStore.search(queryEmbedding, topK, minScore);
    }

    @Override
    public List<RetrievalChunk> fetchAllChunks(int maxDocs) {
        if (embeddingStore == null) {
            return readOnly() ? List.of() : fallbackStore.fetchAllChunks(maxDocs);
        }
        log.warn("Full-corpus fetch is not supported for the LangChain4j Milvus store; hybrid falls back to candidate-pool BM25");
        return List.of();
    }

    @Override
    public void remove(String id) {
        ensureWritable();
        if (embeddingStore != null) {
            embeddingStore.remove(id);
            return;
        }
        fallbackStore.remove(id);
    }

    @Override
    public void removeAll() {
        ensureWritable();
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

    private String fallbackMode() {
        return readOnly() ? "read-only empty result" : "using in-memory fallback";
    }

    private boolean readOnly() {
        return aiProperties.getVectorStore().getMilvus().isReadOnly();
    }

    private void ensureWritable() {
        if (readOnly()) {
            throw new IllegalStateException("Milvus vector store is read-only");
        }
    }

    private boolean collectionExists(AiProperties.Milvus config) {
        if (milvusClient == null) {
            return false;
        }
        try {
            return milvusClient.hasCollection(HasCollectionReq.builder()
                    .collectionName(config.getCollectionName())
                    .build());
        } catch (Exception exception) {
            log.warn("Unable to verify read-only Milvus collection [{}]: {}",
                    config.getCollectionName(), extractMessage(exception));
            return false;
        }
    }
}
