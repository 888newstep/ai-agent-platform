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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdaptiveRagServiceTest {

    @Mock
    private MultiRecallService multiRecallService;

    @Mock
    private PlatformMetricsService metricsService;

    private AdaptiveRagService adaptiveRagService;

    @BeforeEach
    void setUp() {
        AiProperties aiProperties = new AiProperties();
        aiProperties.getRag().getAdaptive().setEnabled(true);
        aiProperties.getRag().setTopK(5);
        aiProperties.getRag().setSimilarityThreshold(0.7);
        when(metricsService.startSample()).thenReturn(Timer.start());

        adaptiveRagService = new AdaptiveRagService(
                aiProperties,
                multiRecallService,
                new QueryRouter(aiProperties),
                new QueryRewriter(aiProperties),
                new SelfRagVerifier(aiProperties),
                metricsService
        );
    }

    @Test
    void shouldBypassRetrievalForDirectQuestions() {
        AdaptiveRagContext context = adaptiveRagService.resolve("hello");

        assertThat(context.getRouteType()).isEqualTo(RagRouteType.DIRECT_ANSWER);
        assertThat(context.getContext()).isEmpty();
        verify(multiRecallService, never()).search(anyString(), anyInt());
    }

    @Test
    void shouldRewriteAndRetrieveForSingleHopQuestions() {
        when(multiRecallService.search(anyString(), eq(5))).thenReturn(List.of(
                RetrievalChunk.builder().id("doc-1").content("RAG 是一种检索增强生成方法").metadata(Map.of()).build()
        ));

        AdaptiveRagContext context = adaptiveRagService.resolve("RAG 是什么");

        assertThat(context.getRouteType()).isEqualTo(RagRouteType.SINGLE_HOP);
        assertThat(context.getRetrievalRounds()).isEqualTo(1);
        assertThat(context.getContext()).contains("Adaptive RAG route: SINGLE_HOP");
        assertThat(context.getRewrittenQuery()).contains("RAG");
        verify(multiRecallService).search(anyString(), eq(5));
        verify(metricsService).recordAdaptiveRag(eq("SINGLE_HOP"), eq("HIGH"), anyBoolean(), eq(1), eq(1), eq(true), any());
    }

    @Test
    void shouldRetryForMultiHopQuestionsWhenEvidenceIsWeak() {
        when(multiRecallService.search(anyString(), eq(5)))
                .thenReturn(List.of(RetrievalChunk.builder().id("doc-1").content("random text").metadata(Map.of()).build()))
                .thenReturn(List.of(RetrievalChunk.builder().id("doc-2").content("RRF 和加权融合的区别在于排序机制").metadata(Map.of()).build()));

        AdaptiveRagContext context = adaptiveRagService.resolve("RRF 和加权融合的区别是什么");

        assertThat(context.getRouteType()).isEqualTo(RagRouteType.MULTI_HOP);
        assertThat(context.getRetrievalRounds()).isEqualTo(2);
        assertThat(context.getContext()).contains("Adaptive RAG route: MULTI_HOP");
        verify(multiRecallService, times(2)).search(anyString(), eq(5));
        verify(metricsService).recordAdaptiveRag(eq("MULTI_HOP"), eq("HIGH"), anyBoolean(), eq(2), eq(1), eq(true), any());
    }
}
