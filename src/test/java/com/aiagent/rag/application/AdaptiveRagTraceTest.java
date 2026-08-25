package com.aiagent.rag.application;

import com.aiagent.infrastructure.config.AiProperties;
import com.aiagent.infrastructure.metrics.PlatformMetricsService;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdaptiveRagTraceTest {

    @Mock private MultiRecallService multiRecallService;
    @Mock private PlatformMetricsService metricsService;
    private AdaptiveRagService adaptiveRagService;

    @BeforeEach
    void setUp() {
        AiProperties aiProperties = new AiProperties();
        aiProperties.getRag().getAdaptive().setEnabled(true);
        aiProperties.getRag().setTopK(5);
        aiProperties.getRag().setSimilarityThreshold(0.7);
        when(metricsService.startSample()).thenReturn(Timer.start());

        adaptiveRagService = new AdaptiveRagService(
                aiProperties, multiRecallService,
                new QueryRouter(aiProperties),
                new QueryRewriter(aiProperties),
                new SelfRagVerifier(aiProperties),
                (query, chunks) -> withSemanticScores(chunks, 0.95),
                metricsService
        );
    }

    @Test
    void shouldPopulateRoundTracesForSingleHop() {
        when(multiRecallService.search(anyString(), eq(5))).thenReturn(List.of(
                RetrievalChunk.builder().id("chunk-1").content("RAG 是一种检索增强生成方法").score(0.85)
                        .metadata(Map.of("retrievalSource", "both", "vectorRank", 1, "bm25Rank", 2, "rrfScore", 0.032))
                        .build()
        ));

        AdaptiveRagContext context = adaptiveRagService.resolve("RAG 是什么");

        assertThat(context.getRoundTraces()).hasSize(1);

        AdaptiveRagRoundTrace trace = context.getRoundTraces().get(0);
        assertThat(trace.getRound()).isEqualTo(1);
        assertThat(trace.getRewrittenQuery()).isNotBlank();
        assertThat(trace.getRetrievedChunks()).hasSize(1);
        assertThat(trace.getChunkCount()).isEqualTo(1);
        assertThat(trace.isTerminal()).isTrue();
        assertThat(trace.getTerminalReason()).isEqualTo("verification_high");

        AdaptiveRagRoundTrace.ChunkTrace chunkTrace = trace.getRetrievedChunks().get(0);
        assertThat(chunkTrace.getChunkId()).isEqualTo("chunk-1");
        assertThat(chunkTrace.getScore()).isEqualTo(0.85);
        assertThat(chunkTrace.getRetrievalSource()).isEqualTo("both");
        assertThat(chunkTrace.getVectorRank()).isEqualTo(1);
        assertThat(chunkTrace.getBm25Rank()).isEqualTo(2);
        assertThat(chunkTrace.getRrfScore()).isEqualTo(0.032);
    }

    @Test
    void shouldPopulateMultipleRoundTracesForMultiHop() {
        when(multiRecallService.search(anyString(), eq(5)))
                .thenReturn(List.of(RetrievalChunk.builder().id("c1").content("random text").score(0.3).metadata(Map.of()).build()))
                .thenReturn(List.of(RetrievalChunk.builder().id("c2").content("RRF 和加权融合的区别在于排序机制").score(0.9).metadata(Map.of()).build()));

        AdaptiveRagContext context = adaptiveRagService.resolve("RRF 和加权融合的区别是什么");

        assertThat(context.getRoundTraces()).hasSize(2);
        assertThat(context.getRoundTraces().get(0).isTerminal()).isFalse();
        assertThat(context.getRoundTraces().get(0).getTerminalReason()).isEqualTo("verification_insufficient");
        assertThat(context.getRoundTraces().get(1).isTerminal()).isTrue();
        assertThat(context.getRoundTraces().get(1).getTerminalReason()).isEqualTo("verification_high");
        assertThat(context.getEndReason()).isEqualTo("verification_high");
    }

    @Test
    void shouldSetEndReasonForDirectAnswer() {
        AdaptiveRagContext context = adaptiveRagService.resolve("hello");

        assertThat(context.getEndReason()).isEqualTo("direct_answer_route");
        assertThat(context.getRoundTraces()).isEmpty();
        assertThat(context.getDecisionConfidence()).isGreaterThan(0.0);
    }

    @Test
    void shouldIncludeDecisionConfidence() {
        when(multiRecallService.search(anyString(), eq(5))).thenReturn(List.of(
                RetrievalChunk.builder().id("c1").content("RAG 检索增强").score(0.8).metadata(Map.of()).build()
        ));

        AdaptiveRagContext context = adaptiveRagService.resolve("RAG 是什么");

        assertThat(context.getDecisionConfidence()).isGreaterThan(0.0);
    }

    private List<RetrievalChunk> withSemanticScores(List<RetrievalChunk> chunks, double score) {
        return chunks.stream().map(chunk -> {
            Map<String, Object> metadata = new java.util.LinkedHashMap<>();
            if (chunk.getMetadata() != null) {
                metadata.putAll(chunk.getMetadata());
            }
            metadata.put(SemanticEvidenceReranker.SEMANTIC_SCORE_METADATA, score);
            return RetrievalChunk.builder()
                    .id(chunk.getId())
                    .content(chunk.getContent())
                    .score(chunk.getScore())
                    .metadata(metadata)
                    .build();
        }).toList();
    }
}
