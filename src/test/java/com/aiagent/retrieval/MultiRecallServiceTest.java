package com.aiagent.retrieval;

import com.aiagent.cache.RagCacheService;
import com.aiagent.config.AiProperties;
import com.aiagent.document.DocumentChunk;
import com.aiagent.document.DocumentService;
import com.aiagent.metrics.PlatformMetricsService;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MultiRecallServiceTest {

    @Mock
    private DocumentService documentService;

    @Mock
    private RagCacheService ragCacheService;

    @Mock
    private PlatformMetricsService metricsService;

    private AiProperties aiProperties;
    private Timer.Sample sample;
    private MultiRecallService multiRecallService;

    @BeforeEach
    void setUp() {
        aiProperties = new AiProperties();
        aiProperties.getRag().setSimilarityThreshold(0.66);
        sample = Timer.start();
        when(metricsService.startSample()).thenReturn(sample);

        multiRecallService = new MultiRecallService(documentService, aiProperties, ragCacheService, metricsService);
    }

    @Test
    void shouldReturnCachedResultsAndTruncateToTopK() {
        List<DocumentChunk> cached = List.of(
                chunk("1", "apple banana"),
                chunk("2", "banana apple"),
                chunk("3", "orange pear")
        );
        when(ragCacheService.getCachedResults("apple")).thenReturn(cached);

        List<DocumentChunk> results = multiRecallService.search("apple", 2);

        assertThat(results).extracting(DocumentChunk::getId).containsExactly("1", "2");
        verify(documentService, never()).searchSimilar(any(), any(Integer.class), any(Double.class));
        verify(metricsService).recordRagSearch(true, 2, sample);
    }

    @Test
    void shouldFuseVectorAndBm25ResultsAndCacheThem() {
        List<DocumentChunk> vectorResults = List.of(
                chunk("1", "apple banana"),
                chunk("2", "apple pie"),
                chunk("3", "banana smoothie")
        );
        when(ragCacheService.getCachedResults("apple banana")).thenReturn(null);
        when(documentService.searchSimilar("apple banana", 50, 0.66)).thenReturn(vectorResults);

        List<DocumentChunk> results = multiRecallService.search("apple banana", 2);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).getId()).isEqualTo("1");
        assertThat(results.get(0).getScore()).isGreaterThan(results.get(1).getScore());
        verify(documentService).searchSimilar("apple banana", 50, 0.66);
        verify(ragCacheService).cacheResults(eq("apple banana"), eq(results));
        verify(metricsService).recordRagSearch(false, 2, sample);
    }

    @Test
    void shouldReturnEmptyWhenVectorSearchFails() {
        when(ragCacheService.getCachedResults("apple")).thenReturn(null);
        when(documentService.searchSimilar("apple", 50, 0.66)).thenThrow(new RuntimeException("Milvus down"));

        List<DocumentChunk> results = multiRecallService.search("apple", 3);

        assertThat(results).isEmpty();
        verify(documentService, times(2)).searchSimilar("apple", 50, 0.66);
        verify(ragCacheService).cacheResults("apple", List.of());
        verify(metricsService).recordRagSearch(false, 0, sample);
    }

    private static DocumentChunk chunk(String id, String content) {
        return DocumentChunk.builder()
                .id(id)
                .content(content)
                .metadata(Map.of("source", id + ".md"))
                .build();
    }
}
