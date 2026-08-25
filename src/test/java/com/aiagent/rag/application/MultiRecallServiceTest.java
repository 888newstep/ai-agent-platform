package com.aiagent.rag.application;

import com.aiagent.infrastructure.cache.RagCacheService;
import com.aiagent.infrastructure.config.AiProperties;
import com.aiagent.infrastructure.metrics.PlatformMetricsService;
import com.aiagent.knowledge.application.DocumentService;
import com.aiagent.knowledge.domain.RetrievalChunk;
import com.aiagent.knowledge.infrastructure.vectorstore.VectorStoreService;
import com.aiagent.shared.exception.KnowledgeRetrievalUnavailableException;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.clearInvocations;
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

    @Mock
    private VectorStoreService vectorStoreService;

    @Mock
    private EcommerceQaLexicalSearchService lexicalSearchService;

    @Mock
    private CrossEncoderRerankClient crossEncoderRerankClient;

    private AiProperties aiProperties;
    private Timer.Sample sample;
    private MultiRecallService multiRecallService;

    @BeforeEach
    void setUp() {
        aiProperties = new AiProperties();
        aiProperties.getRag().setSimilarityThreshold(0.66);
        aiProperties.getRag().setEnableHybridSearch(true);
        aiProperties.getRag().setHybridCorpusBm25Enabled(true);
        aiProperties.getRag().setHybridMysqlFulltextEnabled(false);
        aiProperties.getRag().setHybridMysqlCandidateTopK(50);
        sample = Timer.start();
        when(metricsService.startSample()).thenReturn(sample);

        multiRecallService = new MultiRecallService(
                documentService, aiProperties, ragCacheService, metricsService, vectorStoreService,
                lexicalSearchService, crossEncoderRerankClient, Runnable::run);
    }

    @Test
    void shouldReturnCachedResultsAndTruncateToTopK() {
        List<RetrievalChunk> cached = List.of(
                chunk("1", "apple banana"),
                chunk("2", "banana apple"),
                chunk("3", "orange pear")
        );
        when(ragCacheService.getCachedResults("apple|topK=2|threshold=0.6600|hybrid=true|vw=0.9500|bw=0.0500|rrfK=60|stopwords=true|vTopK=20|bTopK=20|bMaxDocs=5000|corpusBm25=true|mysqlFt=false|mysqlTopK=50|preserveV=1")).thenReturn(cached);

        List<RetrievalChunk> results = multiRecallService.search("apple", 2);

        assertThat(results).extracting(c -> c.getId()).containsExactly("1", "2");
        verify(documentService, never()).searchSimilar(any(), any(Integer.class), any(Double.class));
        verify(metricsService).recordRagSearch(true, 2, sample);
    }

    @Test
    void shouldFuseVectorAndCorpusBm25ResultsAndCacheThem() {
        List<RetrievalChunk> vectorResults = List.of(
                chunk("1", "apple banana"),
                chunk("2", "apple pie"),
                chunk("3", "banana smoothie")
        );
        List<RetrievalChunk> corpus = List.of(
                chunk("1", "apple banana"),
                chunk("2", "apple pie"),
                chunk("3", "banana smoothie")
        );
        when(ragCacheService.getCachedResults("apple banana|topK=2|threshold=0.6600|hybrid=true|vw=0.9500|bw=0.0500|rrfK=60|stopwords=true|vTopK=20|bTopK=20|bMaxDocs=5000|corpusBm25=true|mysqlFt=false|mysqlTopK=50|preserveV=1")).thenReturn(null);
        when(documentService.searchSimilar("apple banana", 50, 0.66)).thenReturn(vectorResults);
        when(vectorStoreService.fetchAllChunks(anyInt())).thenReturn(corpus);

        List<RetrievalChunk> results = multiRecallService.search("apple banana", 2);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).getId()).isEqualTo("1");
        assertThat(results.get(0).getScore()).isGreaterThan(results.get(1).getScore());
        assertThat(results.get(0).getMetadata()).containsEntry("retrievalSource", "both");
        assertThat(results.get(0).getMetadata()).containsKeys("vectorRank", "lexicalRank", "bm25Rank", "rrfScore");
        verify(documentService).searchSimilar("apple banana", 50, 0.66);
        verify(vectorStoreService).fetchAllChunks(anyInt());
        verify(ragCacheService).cacheResults(eq("apple banana|topK=2|threshold=0.6600|hybrid=true|vw=0.9500|bw=0.0500|rrfK=60|stopwords=true|vTopK=20|bTopK=20|bMaxDocs=5000|corpusBm25=true|mysqlFt=false|mysqlTopK=50|preserveV=1"), eq(results));
        verify(metricsService).recordRagSearch(false, 2, sample);
    }

    @Test
    void shouldReportUnavailableWhenVectorSearchFails() {
        when(ragCacheService.getCachedResults("apple|topK=3|threshold=0.6600|hybrid=true|vw=0.9500|bw=0.0500|rrfK=60|stopwords=true|vTopK=20|bTopK=20|bMaxDocs=5000|corpusBm25=true|mysqlFt=false|mysqlTopK=50|preserveV=1")).thenReturn(null);
        when(documentService.searchSimilar(eq("apple"), anyInt(), eq(0.66))).thenThrow(new RuntimeException("Milvus down"));

        assertThatThrownBy(() -> multiRecallService.search("apple", 3))
                .isInstanceOf(KnowledgeRetrievalUnavailableException.class)
                .hasMessage("Knowledge retrieval is temporarily unavailable");
        verify(ragCacheService, never()).cacheResults(anyString(), anyList());
        verify(metricsService).recordRagSearch(false, 0, sample);
    }

    @Test
    void shouldRecallKeywordOnlyDocsViaCorpusBm25() {
        List<RetrievalChunk> corpus = List.of(
                chunk("1", "unrelated semantic doc"),
                chunk("2", "how to reset banana smoothie order"),
                chunk("3", "banana pie recipe")
        );
        when(ragCacheService.getCachedResults("banana smoothie|topK=2|threshold=0.6600|hybrid=true|vw=0.9500|bw=0.0500|rrfK=60|stopwords=true|vTopK=20|bTopK=20|bMaxDocs=5000|corpusBm25=true|mysqlFt=false|mysqlTopK=50|preserveV=1")).thenReturn(null);
        when(documentService.searchSimilar("banana smoothie", 50, 0.66)).thenReturn(List.of());
        when(vectorStoreService.fetchAllChunks(anyInt())).thenReturn(corpus);

        List<RetrievalChunk> results = multiRecallService.search("banana smoothie", 2);

        assertThat(results).hasSize(2);
        assertThat(results).extracting(c -> c.getId()).contains("2", "3");
        assertThat(results).allSatisfy(result ->
                assertThat(result.getMetadata()).containsEntry("retrievalSource", "lexical_only"));
        verify(vectorStoreService).fetchAllChunks(anyInt());
    }

    @Test
    void shouldFallbackToVectorCandidatesWhenCorpusIsTruncated() {
        aiProperties.getRag().setHybridBm25CorpusMaxDocs(3);
        List<RetrievalChunk> truncatedCorpus = List.of(
                chunk("10", "first corpus row"),
                chunk("11", "second corpus row"),
                chunk("12", "third corpus row")
        );
        List<RetrievalChunk> vectorCandidates = List.of(
                chunk("1", "refund conditions and process"),
                chunk("2", "shipping schedule")
        );
        String cacheKey = "refund conditions|topK=2|threshold=0.6600|hybrid=true"
                + "|vw=0.9500|bw=0.0500|rrfK=60|stopwords=true|vTopK=20|bTopK=20|bMaxDocs=3|corpusBm25=true|mysqlFt=false|mysqlTopK=50|preserveV=1";
        when(ragCacheService.getCachedResults(cacheKey)).thenReturn(null);
        when(documentService.searchSimilar("refund conditions", 50, 0.66)).thenReturn(vectorCandidates);
        when(vectorStoreService.fetchAllChunks(3)).thenReturn(truncatedCorpus);

        List<RetrievalChunk> results = multiRecallService.search("refund conditions", 2);
        verify(vectorStoreService).fetchAllChunks(3);
        clearInvocations(vectorStoreService);
        List<RetrievalChunk> repeatedResults = multiRecallService.search("refund conditions", 2);

        assertThat(results).extracting(RetrievalChunk::getId).containsExactly("1", "2");
        assertThat(repeatedResults).extracting(RetrievalChunk::getId).containsExactly("1", "2");
        verifyNoInteractions(vectorStoreService);
        verify(ragCacheService, times(2)).cacheResults(cacheKey, results);
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
    void shouldFuseIndependentMysqlLexicalCandidates() {
        aiProperties.getRag().setHybridMysqlFulltextEnabled(true);
        aiProperties.getRag().setHybridCorpusBm25Enabled(false);
        aiProperties.getRag().setHybridVectorWeight(0.5);
        aiProperties.getRag().setHybridBm25Weight(0.5);
        List<RetrievalChunk> vectorResults = List.of(
                chunk("vector-1", "semantic candidate"),
                chunk("shared", "shared candidate")
        );
        List<RetrievalChunk> lexicalResults = List.of(
                chunk("lexical-1", "exact keyword candidate"),
                chunk("shared", "shared candidate")
        );
        String cacheKey = "exact keyword|topK=3|threshold=0.6600|hybrid=true"
                + "|vw=0.5000|bw=0.5000|rrfK=60|stopwords=true|vTopK=20|bTopK=20|bMaxDocs=5000|corpusBm25=false|mysqlFt=true|mysqlTopK=50|preserveV=1";
        when(ragCacheService.getCachedResults(cacheKey)).thenReturn(null);
        when(lexicalSearchService.search("exact keyword", 50)).thenReturn(lexicalResults);
        when(documentService.searchSimilar("exact keyword", 3, 0.66)).thenReturn(vectorResults);

        List<RetrievalChunk> results = multiRecallService.search("exact keyword", 3);

        assertThat(results).extracting(RetrievalChunk::getId)
                .containsExactly("vector-1", "shared", "lexical-1");
        assertThat(results.get(1).getMetadata()).containsEntry("retrievalSource", "both");
        assertThat(results.get(2).getMetadata()).containsEntry("retrievalSource", "lexical_only");
        verifyNoInteractions(vectorStoreService);
    }

    @Test
    void shouldCrossEncoderRerankHybridCandidatesAndPreserveVectorTopOne() {
        aiProperties.getRag().setHybridMysqlFulltextEnabled(true);
        aiProperties.getRag().setHybridCorpusBm25Enabled(false);
        aiProperties.getRag().setHybridCrossEncoderEnabled(true);
        aiProperties.getRag().setHybridRerankCandidateTopK(4);
        List<RetrievalChunk> vectorResults = List.of(
                chunk("vector-top", "vector top"),
                chunk("vector-second", "vector second")
        );
        List<RetrievalChunk> expandedVectorResults = List.of(
                chunk("vector-top", "vector top"),
                chunk("vector-second", "vector second"),
                chunk("vector-expanded", "expanded semantic candidate")
        );
        List<RetrievalChunk> lexicalResults = List.of(
                chunk("lexical-best", "lexical best"),
                chunk("lexical-other", "lexical other")
        );
        String cacheKey = "refund|topK=3|threshold=0.6600|hybrid=true"
                + "|vw=0.9500|bw=0.0500|rrfK=60|stopwords=true|vTopK=20|bTopK=20|bMaxDocs=5000|corpusBm25=false|mysqlFt=true|mysqlTopK=50|preserveV=1"
                + "|cross=true|rerankTopK=4|rerankFailOpen=true";
        when(ragCacheService.getCachedResults(cacheKey)).thenReturn(null);
        when(documentService.searchSimilar("refund", List.of(3, 20), 0.66)).thenReturn(Map.of(
                3, vectorResults,
                20, expandedVectorResults));
        when(lexicalSearchService.search("refund", 50)).thenReturn(lexicalResults);
        when(crossEncoderRerankClient.rerank(eq("refund"), anyList())).thenReturn(List.of(
                new CrossEncoderRerankClient.RerankScore(0, 0.40),
                new CrossEncoderRerankClient.RerankScore(1, 0.50),
                new CrossEncoderRerankClient.RerankScore(2, 0.98),
                new CrossEncoderRerankClient.RerankScore(3, 0.99)
        ));

        List<RetrievalChunk> results = multiRecallService.search("refund", 3);

        assertThat(results).extracting(RetrievalChunk::getId)
                .containsExactly("vector-top", "lexical-best", "vector-expanded");
        assertThat(results.get(1).getMetadata())
                .containsEntry("hybridRerankProvider", "cross-encoder")
                .containsEntry("hybridRerankScore", 0.99)
                .containsEntry("retrievalSource", "lexical_only");
        assertThat(results.get(2).getMetadata())
                .containsEntry("retrievalSource", "vector_only");
        verify(documentService).searchSimilar("refund", List.of(3, 20), 0.66);
    }

    @Test
    void shouldFallbackToRrfWhenHybridCrossEncoderFails() {
        aiProperties.getRag().setHybridMysqlFulltextEnabled(true);
        aiProperties.getRag().setHybridCorpusBm25Enabled(false);
        aiProperties.getRag().setHybridCrossEncoderEnabled(true);
        when(ragCacheService.getCachedResults(anyString())).thenReturn(null);
        when(documentService.searchSimilar("refund", List.of(2, 20), 0.66)).thenReturn(Map.of(
                2, List.of(chunk("vector", "vector")),
                20, List.of(chunk("vector", "vector"))));
        when(lexicalSearchService.search("refund", 50)).thenReturn(List.of(chunk("lexical", "lexical")));
        when(crossEncoderRerankClient.rerank(eq("refund"), anyList()))
                .thenThrow(new IllegalStateException("reranker down"));

        List<RetrievalChunk> results = multiRecallService.search("refund", 2);

        assertThat(results).extracting(RetrievalChunk::getId).containsExactly("vector", "lexical");
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

        assertThat(results).extracting(c -> c.getId()).containsExactly("1");
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
