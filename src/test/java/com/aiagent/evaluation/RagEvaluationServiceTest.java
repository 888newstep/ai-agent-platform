package com.aiagent.evaluation;

import com.aiagent.config.AiProperties;
import com.aiagent.document.DocumentChunk;
import com.aiagent.retrieval.MultiRecallService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RagEvaluationServiceTest {

    @Mock
    private MultiRecallService multiRecallService;

    private RagEvaluationService ragEvaluationService;

    @BeforeEach
    void setUp() {
        AiProperties aiProperties = new AiProperties();
        aiProperties.getRag().setTopK(5);
        aiProperties.getRag().setSimilarityThreshold(0.68);
        aiProperties.getRag().setEnableHybridSearch(true);
        aiProperties.getDocument().setChunkSize(400);
        aiProperties.getDocument().setChunkOverlap(80);
        ragEvaluationService = new RagEvaluationService(multiRecallService, aiProperties);
    }

    @Test
    void shouldEvaluateRecallPrecisionAndSnapshot() {
        Map<String, List<String>> dataset = new LinkedHashMap<>();
        dataset.put("q1", List.of("doc-1", "doc-2"));
        dataset.put("q2", List.of());

        when(multiRecallService.search("q1", 1)).thenReturn(List.of(chunk("doc-1")));
        when(multiRecallService.search("q1", 2)).thenReturn(List.of(chunk("doc-1"), chunk("other")));
        when(multiRecallService.search("q2", 1)).thenReturn(List.of());
        when(multiRecallService.search("q2", 2)).thenReturn(List.of(chunk("other")));

        RagEvaluationService.EvaluationReport report = ragEvaluationService.evaluate(dataset, List.of(1, 2));

        assertThat(report.getDatasetSize()).isEqualTo(2);
        assertThat(report.getTopKs()).containsExactly(1, 2);
        assertThat(report.getConfigSnapshot())
                .containsEntry("topK", 5)
                .containsEntry("similarityThreshold", 0.68)
                .containsEntry("hybridSearch", true)
                .containsEntry("chunkSize", 400)
                .containsEntry("chunkOverlap", 80);

        Map<String, Object> k1 = report.getMetrics().get("1");
        Map<String, Object> k2 = report.getMetrics().get("2");
        assertThat(((Number) k1.get("recall")).doubleValue()).isCloseTo(0.25, within(0.001));
        assertThat(((Number) k1.get("precision")).doubleValue()).isCloseTo(0.5, within(0.001));
        assertThat(((Number) k1.get("f1")).doubleValue()).isCloseTo(0.3333, within(0.01));
        assertThat(((Number) k2.get("recall")).doubleValue()).isCloseTo(0.25, within(0.001));
        assertThat(((Number) k2.get("precision")).doubleValue()).isCloseTo(0.25, within(0.001));
        assertThat(((Number) k2.get("f1")).doubleValue()).isCloseTo(0.25, within(0.001));
        assertThat(report.toFormattedSummary()).contains("RAG");
    }

    @Test
    void shouldQuickEvaluateUsingBuiltInDataset() {
        when(multiRecallService.search(anyString(), eq(1))).thenReturn(List.of());

        RagEvaluationService.EvaluationReport report = ragEvaluationService.quickEvaluate(List.of(1));

        assertThat(report.getDatasetSize()).isEqualTo(5);
        assertThat(report.getMetrics()).containsKey("1");
        assertThat(report.getMetrics().get("1")).containsKeys("recall", "precision", "f1", "avgLatency", "p99Latency", "p50Latency");
    }

    private static DocumentChunk chunk(String id) {
        return DocumentChunk.builder().id(id).content(id).build();
    }
}
