package com.aiagent.retrieval;

import com.aiagent.document.DocumentChunk;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class Bm25SearchTest {

    @Test
    void shouldReturnEmptyWhenDocumentsAreEmpty() {
        Bm25Search search = new Bm25Search(List.of());

        assertThat(search.search("apple", 3)).isEmpty();
    }

    @Test
    void shouldReturnEmptyWhenQueryHasNoTokens() {
        Bm25Search search = new Bm25Search(List.of(chunk("1", "apple banana")));

        assertThat(search.search("   ", 3)).isEmpty();
        assertThat(search.search("!!!", 3)).isEmpty();
    }

    @Test
    void shouldRankDocumentsByTermRelevanceAndRespectTopK() {
        Bm25Search search = new Bm25Search(List.of(
                chunk("1", "apple apple banana"),
                chunk("2", "apple"),
                chunk("3", "orange pear")
        ));

        List<DocumentChunk> results = search.search("apple banana", 2);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).getId()).isEqualTo("1");
        assertThat(results.get(1).getId()).isEqualTo("2");
        assertThat(results.get(0).getScore()).isGreaterThan(results.get(1).getScore());
    }

    @Test
    void shouldPreserveMetadataInReturnedChunks() {
        DocumentChunk original = DocumentChunk.builder()
                .id("doc-1")
                .content("订单 查询 订单")
                .metadata(Map.of("source", "faq.md"))
                .build();
        Bm25Search search = new Bm25Search(List.of(original));

        List<DocumentChunk> results = search.search("订单", 1);

        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.getId()).isEqualTo("doc-1");
            assertThat(result.getMetadata()).containsEntry("source", "faq.md");
            assertThat(result.getScore()).isPositive();
        });
    }

    private static DocumentChunk chunk(String id, String content) {
        return DocumentChunk.builder()
                .id(id)
                .content(content)
                .metadata(Map.of("id", id))
                .build();
    }
}
