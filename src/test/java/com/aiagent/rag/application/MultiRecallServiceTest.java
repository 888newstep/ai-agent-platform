package com.aiagent.rag.application;

import com.aiagent.infrastructure.cache.RagCacheService;
import com.aiagent.infrastructure.config.AiProperties;
import com.aiagent.infrastructure.metrics.PlatformMetricsService;
import com.aiagent.knowledge.application.DocumentService;
import com.aiagent.knowledge.domain.RetrievalChunk;
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
import static org.mockito.Mockito.verifyNoInteractions;
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
        aiProperties.getRag().setEnableHybridSearch(true);
        sample = Timer.start();
        when(metricsService.startSample()).thenReturn(sample);

        multiRecallService = new MultiRecallService(documentService, aiProperties, ragCacheService, metricsService);
    }

    @Test
    void shouldReturnCachedResultsAndTruncateToTopK() {
        List<RetrievalChunk> cached = List.of(
                chunk("1", "apple banana"),
                chunk("2", "banana apple"),
                chunk("3", "orange pear")
        );
        when(ragCacheService.getCachedResults("apple|topK=2|threshold=0.6600|hybrid=true")).thenReturn(cached);

        List<RetrievalChunk> results = multiRecallService.search("apple", 2);

        assertThat(results).extracting(RetrievalChunk::getId).containsExactly("1", "2");
        verify(documentService, never()).searchSimilar(any(), any(Integer.class), any(Double.class));
        verify(metricsService).recordRagSearch(true, 2, sample);
    }

    @Test
    void shouldFuseVectorAndBm25ResultsAndCacheThem() {
        List<RetrievalChunk> vectorResults = List.of(
                chunk("1", "apple banana"),
                chunk("2", "apple pie"),
                chunk("3", "banana smoothie")
        );
        when(ragCacheService.getCachedResults("apple banana|topK=2|threshold=0.6600|hybrid=true")).thenReturn(null);
        when(documentService.searchSimilar("apple banana", 50, 0.66)).thenReturn(vectorResults);

        List<RetrievalChunk> results = multiRecallService.search("apple banana", 2);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).getId()).isEqualTo("1");
        assertThat(results.get(0).getScore()).isGreaterThan(results.get(1).getScore());
        assertThat(results.get(0).getMetadata()).containsEntry("retrievalSource", "both");
        assertThat(results.get(0).getMetadata()).containsKeys("vectorRank", "bm25Rank", "rrfScore");
        verify(documentService).searchSimilar("apple banana", 50, 0.66);
        verify(ragCacheService).cacheResults(eq("apple banana|topK=2|threshold=0.6600|hybrid=true"), eq(results));
        verify(metricsService).recordRagSearch(false, 2, sample);
    }

    @Test
    void shouldReturnEmptyWhenVectorSearchFails() {
        when(ragCacheService.getCachedResults("apple|topK=3|threshold=0.6600|hybrid=true")).thenReturn(null);
        when(documentService.searchSimilar("apple", 50, 0.66)).thenThrow(new RuntimeException("Milvus down"));

        List<RetrievalChunk> results = multiRecallService.search("apple", 3);

        assertThat(results).isEmpty();
        verify(documentService, times(2)).searchSimilar("apple", 50, 0.66);
        verify(ragCacheService).cacheResults("apple|topK=3|threshold=0.6600|hybrid=true", List.of());
        verify(metricsService).recordRagSearch(false, 0, sample);
    }

    @Test
    void shouldUseVectorOnlyModeWhenHybridDisabled() {
        List<RetrievalChunk> vectorResults = List.of(
                chunk("1", "semantic-only content"),
                chunk("2", "another unrelated passage")
        );
        MultiRecallService.SearchOptions options = MultiRecallService.SearchOptions.builder()
                .topK(2)
                .similarityThreshold(0.66)
                .hybridSearch(false)
                .cacheEnabled(true)
                .build();
        when(ragCacheService.getCachedResults("unmatched keyword|topK=2|threshold=0.6600|hybrid=false")).thenReturn(null);
        when(documentService.searchSimilar("unmatched keyword", 2, 0.66)).thenReturn(vectorResults);

        List<RetrievalChunk> results = multiRecallService.search("unmatched keyword", options);

        assertThat(results).hasSize(2);
        assertThat(results)
                .allSatisfy(chunk -> assertThat(chunk.getMetadata()).containsEntry("retrievalSource", "vector_only"));
        assertThat(results.get(0).getMetadata()).containsEntry("vectorRank", 1);
        verify(documentService).searchSimilar("unmatched keyword", 2, 0.66);
        verify(documentService, never()).searchSimilar("unmatched keyword", 50, 0.66);
        verify(ragCacheService).cacheResults(eq("unmatched keyword|topK=2|threshold=0.6600|hybrid=false"), eq(results));
        verify(metricsService).recordRagSearch(false, 2, sample);
    }

    @Test
    void shouldSkipCacheWhenDisabled() {
        List<RetrievalChunk> vectorResults = List.of(chunk("1", "fresh query result"));
        MultiRecallService.SearchOptions options = MultiRecallService.SearchOptions.builder()
                .topK(2)
                .similarityThreshold(0.66)
                .hybridSearch(false)
                .cacheEnabled(false)
                .build();
        when(documentService.searchSimilar("fresh query", 2, 0.66)).thenReturn(vectorResults);

        List<RetrievalChunk> results = multiRecallService.search("fresh query", options);

        assertThat(results).extracting(RetrievalChunk::getId).containsExactly("1");
        verifyNoInteractions(ragCacheService);
        verify(metricsService).recordRagSearch(false, 1, sample);
    }

    private static RetrievalChunk chunk(String id, String content) {
        return RetrievalChunk.builder()
                .id(id)
                .content(content)
                .metadata(Map.of("source", id + ".md"))
                .build();
    }
}