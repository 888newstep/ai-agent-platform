package com.aiagent.vectorstore;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class InMemoryVectorStoreServiceTest {
    private InMemoryVectorStoreService service;
    @BeforeEach void setUp() { service = new InMemoryVectorStoreService(); }

    @Test void shouldAddEmbedding() {
        assertDoesNotThrow(() -> service.add("id1", new Embedding(new float[]{1.0f, 2.0f})));
    }
    @Test void shouldAddNullId() {
        assertDoesNotThrow(() -> service.add(null, new Embedding(new float[]{1.0f})));
    }
    @Test void shouldAddAll() {
        List<Embedding> embs = List.of(new Embedding(new float[]{1.0f}), new Embedding(new float[]{0.0f}));
        List<TextSegment> segs = List.of(TextSegment.from("a"), TextSegment.from("b"));
        assertEquals(2, service.addAll(embs, segs).size());
    }
    @Test void shouldSearch() {
        service.add("id1", new Embedding(new float[]{1.0f, 0.0f}));
        List<EmbeddingMatch<TextSegment>> r = service.search(new Embedding(new float[]{1.0f, 0.0f}), 5, 0.0);
        assertFalse(r.isEmpty());
    }
    @Test void shouldRemove() { assertDoesNotThrow(() -> service.remove("id1")); }
    @Test void shouldRemoveAll() { assertDoesNotThrow(() -> service.removeAll()); }
}
