package com.aiagent.knowledge.infrastructure.vectorstore;

import com.aiagent.infrastructure.config.AiProperties;
import com.aiagent.knowledge.domain.RetrievalChunk;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.request.QueryReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.QueryResp;
import io.milvus.v2.service.vector.response.SearchResp;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Milvus V2 适配器，读取项目已有的 QA collection schema。
 * 该模式用于 question/answer/qa_text/qa_pair_id/embedding 字段，不依赖 LangChain4j 默认 schema。
 */
@Slf4j
@Service
@Primary
@ConditionalOnProperty(prefix = "ai.vector-store", name = "type", havingValue = "milvus", matchIfMissing = true)
@ConditionalOnProperty(prefix = "ai.vector-store", name = "mode", havingValue = "qa")
public class MilvusQaVectorStoreService implements VectorStoreService {

    private static final String EMBEDDING_FIELD = "embedding";
    private static final long CORPUS_FETCH_LIMIT = 1000;
    private static final List<String> OUTPUT_FIELDS = List.of(
            "id", "question", "answer", "qa_text", "qa_pair_id", "category", "source_file", "ts");

    private final AiProperties aiProperties;
    private final MilvusClientV2 milvusClient;
    private final InMemoryVectorStoreService fallbackStore = new InMemoryVectorStoreService();
    private boolean available;

    public MilvusQaVectorStoreService(
            AiProperties aiProperties,
            @Autowired(required = false) MilvusClientV2 milvusClient) {
        this.aiProperties = aiProperties;
        this.milvusClient = milvusClient;
    }

    @PostConstruct
    public void init() {
        if (milvusClient == null) {
            log.warn("Milvus V2 client unavailable; QA vector search {}", fallbackMode());
            return;
        }

        String collectionName = collectionName();
        try {
            if (!milvusClient.hasCollection(HasCollectionReq.builder()
                    .collectionName(collectionName)
                    .build())) {
                log.warn("QA collection [{}] does not exist; {}", collectionName, fallbackMode());
                return;
            }
            milvusClient.loadCollection(LoadCollectionReq.builder()
                    .collectionName(collectionName)
                    .build());
            available = true;
            log.info("Connected to Milvus QA collection [{}]", collectionName);
        } catch (Exception exception) {
            log.warn("Failed to initialize Milvus QA collection [{}]: {}",
                    collectionName, rootMessage(exception));
        }
    }

    @Override
    public void add(String id, Embedding embedding) {
        if (readOnly()) {
            throw new IllegalStateException("Milvus QA collection is read-only");
        }
        if (!available) {
            requireFallbackEnabled();
            fallbackStore.add(id, embedding);
            return;
        }
        if (!StringUtils.hasText(id) || embedding == null) {
            throw new IllegalArgumentException("QA vector id and embedding are required");
        }
        addAll(List.of(embedding), List.of(TextSegment.from(id, Metadata.from("qa_pair_id", id))));
    }

    @Override
    public List<String> addAll(List<Embedding> embeddings, List<TextSegment> segments) {
        if (embeddings == null || segments == null || embeddings.size() != segments.size()) {
            throw new IllegalArgumentException("Embeddings and segments must have the same size");
        }
        if (readOnly()) {
            throw new IllegalStateException("Milvus QA collection is read-only");
        }
        if (!available) {
            requireFallbackEnabled();
            return fallbackStore.addAll(embeddings, segments);
        }

        List<JsonObject> rows = new ArrayList<>(segments.size());
        for (int index = 0; index < segments.size(); index++) {
            Embedding embedding = embeddings.get(index);
            TextSegment segment = segments.get(index);
            if (embedding == null || segment == null) {
                throw new IllegalArgumentException("Embedding and segment must not be null");
            }
            rows.add(toRow(embedding, segment));
        }

        var response = milvusClient.insert(InsertReq.builder()
                .collectionName(collectionName())
                .data(rows)
                .build());
        if (response.getPrimaryKeys() == null) {
            return List.of();
        }
        return response.getPrimaryKeys().stream().map(String::valueOf).toList();
    }

    @Override
    public List<EmbeddingMatch<TextSegment>> search(Embedding queryEmbedding, int topK, double minScore) {
        if (queryEmbedding == null || topK <= 0) {
            return List.of();
        }
        if (!available) {
            return fallbackEnabled() && !readOnly()
                    ? fallbackStore.search(queryEmbedding, topK, minScore)
                    : List.of();
        }
        return searchInCollection(collectionName(), queryEmbedding, topK, minScore);
    }

    @Override
    public List<EmbeddingMatch<TextSegment>> searchFaq(Embedding queryEmbedding, int topK, double minScore) {
        if (queryEmbedding == null || topK <= 0 || !available) {
            return List.of();
        }
        return searchInCollection(faqCollectionName(), queryEmbedding, topK, minScore);
    }

    private List<EmbeddingMatch<TextSegment>> searchInCollection(String targetCollection,
                                                                 Embedding queryEmbedding,
                                                                 int topK,
                                                                 double minScore) {
        // HNSW 要求 ef >= topK，候选池扩容时动态放大 ef（上限 1024 控制开销）
        int ef = Math.max(64, Math.min(topK * 4, 1024));
        SearchReq request = SearchReq.builder()
                .collectionName(targetCollection)
                .data(List.of(new FloatVec(queryEmbedding.vector())))
                .annsField(EMBEDDING_FIELD)
                .metricType(IndexParam.MetricType.COSINE)
                .topK(topK)
                .outputFields(OUTPUT_FIELDS)
                .searchParams(Map.of("ef", ef))
                .build();

        SearchResp response = milvusClient.search(request);
        if (response == null || response.getSearchResults() == null || response.getSearchResults().isEmpty()) {
            return List.of();
        }

        List<EmbeddingMatch<TextSegment>> matches = new ArrayList<>();
        for (SearchResp.SearchResult result : response.getSearchResults().get(0)) {
            if (result == null || result.getScore() == null || result.getScore() < minScore) {
                continue;
            }
            Map<String, Object> entity = result.getEntity() == null ? Map.of() : result.getEntity();
            String id = resolveId(result, entity);
            String text = firstText(entity, "qa_text", "answer", "question");
            if (!StringUtils.hasText(id) || !StringUtils.hasText(text)) {
                continue;
            }
            matches.add(new EmbeddingMatch<>(
                    result.getScore().doubleValue(),
                    id,
                    null,
                    TextSegment.from(text, toMetadata(entity))));
        }
        return matches;
    }

    @Override
    public List<RetrievalChunk> fetchAllChunks(int maxDocs) {
        if (!available) {
            return fallbackEnabled() && !readOnly() ? fallbackStore.fetchAllChunks(maxDocs) : List.of();
        }
        QueryResp response = milvusClient.query(QueryReq.builder()
                .collectionName(collectionName())
                .filter("qa_pair_id >= 0")
                .outputFields(OUTPUT_FIELDS)
                .offset(0)
                .limit(maxDocs > 0 ? maxDocs : CORPUS_FETCH_LIMIT)
                .build());
        List<RetrievalChunk> chunks = new ArrayList<>();
        if (response == null || response.getQueryResults() == null) {
            return chunks;
        }
        for (QueryResp.QueryResult result : response.getQueryResults()) {
            Map<String, Object> entity = result.getEntity() == null ? Map.of() : result.getEntity();
            String id = firstText(entity, "qa_pair_id", "id");
            String text = firstText(entity, "qa_text", "answer", "question");
            if (!StringUtils.hasText(id) || !StringUtils.hasText(text)) {
                continue;
            }
            chunks.add(RetrievalChunk.builder()
                .id(id)
                .content(text)
                .score(0.0)
                .metadata(toMetadata(entity).toMap())
                .build());
        }
        log.info("Fetched {} chunks from QA collection [{}]", chunks.size(), collectionName());
        return chunks;
    }

    @Override
    public void remove(String id) {
        if (readOnly()) {
            throw new IllegalStateException("Milvus QA collection is read-only");
        }
        if (!available) {
            requireFallbackEnabled();
            fallbackStore.remove(id);
            return;
        }
        long qaPairId = parseLongId(id);
        milvusClient.delete(DeleteReq.builder()
                .collectionName(collectionName())
                .filter("qa_pair_id == " + qaPairId)
                .build());
    }

    @Override
    public void removeAll() {
        if (readOnly()) {
            throw new IllegalStateException("Milvus QA collection is read-only");
        }
        if (!available) {
            requireFallbackEnabled();
            fallbackStore.removeAll();
            return;
        }
        throw new UnsupportedOperationException("Bulk deletion of a QA collection is disabled");
    }

    private JsonObject toRow(Embedding embedding, TextSegment segment) {
        Map<String, Object> metadata = segment.metadata().toMap();
        String qaPairId = firstText(metadata, "qa_pair_id");
        if (!StringUtils.hasText(qaPairId)) {
            throw new IllegalArgumentException("QA writes require metadata.qa_pair_id");
        }

        String text = limit(segment.text(), 3072);
        JsonObject row = new JsonObject();
        row.addProperty("question", limit(firstText(metadata, "question", "query", "text"), 1024, text));
        row.addProperty("answer", limit(firstText(metadata, "answer"), 2048, text));
        row.addProperty("qa_text", text);
        row.addProperty("qa_pair_id", parseLongId(qaPairId));
        row.addProperty("category", limit(firstText(metadata, "category"), 100, "document"));
        row.add("embedding", toJsonArray(embedding.vector()));
        row.addProperty("ts", System.currentTimeMillis() / 1000);
        return row;
    }

    private String resolveId(SearchResp.SearchResult result, Map<String, Object> entity) {
        String qaPairId = firstText(entity, "qa_pair_id");
        if (StringUtils.hasText(qaPairId)) {
            return qaPairId;
        }
        String primaryId = firstText(entity, "id");
        return StringUtils.hasText(primaryId) ? primaryId : String.valueOf(result.getId());
    }

    private Metadata toMetadata(Map<String, Object> entity) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        entity.forEach((key, value) -> {
            if (value != null && !EMBEDDING_FIELD.equals(key)) {
                metadata.put(key, String.valueOf(value));
            }
        });
        return Metadata.from(metadata);
    }

    private JsonArray toJsonArray(float[] vector) {
        JsonArray array = new JsonArray();
        for (float value : vector) {
            array.add(value);
        }
        return array;
    }

    private String firstText(Map<String, Object> values, String... keys) {
        for (String key : keys) {
            Object value = values.get(key);
            if (value != null && StringUtils.hasText(String.valueOf(value))) {
                return String.valueOf(value);
            }
        }
        return null;
    }

    private String limit(String value, int maxLength, String fallback) {
        String resolved = StringUtils.hasText(value) ? value : fallback;
        return limit(resolved, maxLength);
    }

    private String limit(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private long parseLongId(String id) {
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("QA pair id must be numeric: " + id, exception);
        }
    }

    private String collectionName() {
        return aiProperties.getVectorStore().getMilvus().getCollectionName();
    }

    private String faqCollectionName() {
        return aiProperties.getVectorStore().getMilvus().getFaqCollectionName();
    }

    private String fallbackMode() {
        return fallbackEnabled() && !readOnly()
                ? "using explicitly enabled in-memory fallback"
                : "vector operations disabled";
    }

    private boolean fallbackEnabled() {
        return aiProperties.getVectorStore().getMilvus().isFallbackEnabled();
    }

    @Override
    public boolean isAvailable() {
        return available;
    }

    @Override
    public boolean isWriteAvailable() {
        return !readOnly() && (available || fallbackEnabled());
    }

    private void requireFallbackEnabled() {
        if (!fallbackEnabled()) {
            throw new IllegalStateException("Milvus QA collection is unavailable and in-memory fallback is disabled");
        }
    }

    private boolean readOnly() {
        return aiProperties.getVectorStore().getMilvus().isReadOnly();
    }

    private String rootMessage(Exception exception) {
        Throwable current = exception;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
