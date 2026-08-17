package com.aiagent.knowledge.infrastructure.vectorstore;

import com.aiagent.infrastructure.config.AiProperties;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MilvusReadOnlyModeTest {

    @Test
    void genericStoreRejectsWritesAndDoesNotUseFallbackForReads() {
        AiProperties properties = readOnlyProperties();
        MilvusVectorStoreService store = new MilvusVectorStoreService(properties, Optional.empty());
        Embedding embedding = new Embedding(new float[]{1.0f, 0.0f});

        assertThrows(IllegalStateException.class, () -> store.add("id", embedding));
        assertThrows(IllegalStateException.class, () -> store.addAll(List.of(embedding), List.of(TextSegment.from("text"))));
        assertThrows(IllegalStateException.class, () -> store.remove("id"));
        assertThrows(IllegalStateException.class, store::removeAll);
        assertEquals(List.of(), store.search(embedding, 5, 0.0));
        assertEquals(List.of(), store.fetchAllChunks(10));
    }

    @Test
    void qaStoreRejectsWritesAndReturnsEmptyWhenMilvusIsUnavailable() {
        AiProperties properties = readOnlyProperties();
        MilvusQaVectorStoreService store = new MilvusQaVectorStoreService(properties, null);
        Embedding embedding = new Embedding(new float[]{1.0f, 0.0f});

        assertThrows(IllegalStateException.class, () -> store.add("1", embedding));
        assertThrows(IllegalStateException.class, () -> store.addAll(List.of(embedding), List.of(TextSegment.from("text"))));
        assertThrows(IllegalStateException.class, () -> store.remove("1"));
        assertThrows(IllegalStateException.class, store::removeAll);
        assertEquals(List.<EmbeddingMatch<TextSegment>>of(), store.search(embedding, 5, 0.0));
        assertEquals(List.of(), store.fetchAllChunks(10));
    }

    @Test
    void adminStoreSkipsMutatingOperationsInReadOnlyMode() throws Exception {
        AiProperties properties = readOnlyProperties();
        MilvusAdminService adminService = new MilvusAdminService(null, properties);

        assertThrows(IllegalStateException.class, () -> adminService.createIndex("ai_agent_documents"));
        assertDoesNotThrow(() -> adminService.flushAll());
        adminService.buildAllIndexesAsync().get(1, TimeUnit.SECONDS);
    }

    private AiProperties readOnlyProperties() {
        AiProperties properties = new AiProperties();
        properties.getVectorStore().getMilvus().setReadOnly(true);
        return properties;
    }
}