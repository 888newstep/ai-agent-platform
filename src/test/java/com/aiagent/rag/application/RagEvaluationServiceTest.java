package com.aiagent.rag.application;

import com.aiagent.infrastructure.config.AiProperties;
import com.aiagent.knowledge.domain.RetrievalChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RagEvaluationServiceTest {

    @TempDir
    Path tempDir;

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
        dataset.put("q2", List.of("doc-3"));

        when(multiRecallService.search(eq("q1"), any(MultiRecallService.SearchOptions.class)))
                .thenAnswer(invocation -> ((MultiRecallService.SearchOptions) invocation.getArgument(1)).getTopK() == 1
                        ? List.of(chunk("doc-1"))
                        : List.of(chunk("doc-1"), chunk("other")));
        when(multiRecallService.search(eq("q2"), any(MultiRecallService.SearchOptions.class)))
                .thenAnswer(invocation -> ((MultiRecallService.SearchOptions) invocation.getArgument(1)).getTopK() == 1
                        ? List.of()
                        : List.of(chunk("other")));

        RagEvaluationService.EvaluationReport report = ragEvaluationService.evaluate(dataset, List.of(1, 2));

        assertThat(report.getDatasetSize()).isEqualTo(2);
        assertThat(report.getDatasetSource()).isEqualTo("inline");
        assertThat(report.getTopKs()).containsExactly(1, 2);
        assertThat(report.getConfigSnapshot())
                .containsEntry("profile", "default")
                .containsEntry("topK", 5)
                .containsEntry("similarityThreshold", 0.68)
                .containsEntry("hybridSearch", true)
                .containsEntry("chunkSize", 400)
                .containsEntry("chunkOverlap", 80);
        assertThat(report.getCategoryMetrics()).containsKey("uncategorized");

        Map<String, Object> k1 = report.getMetrics().get("1");
        Map<String, Object> k2 = report.getMetrics().get("2");
        assertThat(k1).containsKeys("sampleCount", "p95Latency", "emptyResultCount", "emptyResultRate", "retrievalHitRate");
        assertThat(k1.get("emptyResultCount")).isEqualTo(1);
        assertThat(((Number) k1.get("emptyResultRate")).doubleValue()).isCloseTo(0.5, within(0.001));
        assertThat(((Number) k1.get("retrievalHitRate")).doubleValue()).isCloseTo(0.5, within(0.001));
        assertThat(k1.get("sampleCount")).isEqualTo(2);
        assertThat(((Number) k1.get("recall")).doubleValue()).isCloseTo(0.25, within(0.001));
        assertThat(((Number) k1.get("precision")).doubleValue()).isCloseTo(0.5, within(0.001));
        assertThat(((Number) k1.get("f1")).doubleValue()).isCloseTo(0.3333, within(0.01));
        assertThat(((Number) k2.get("recall")).doubleValue()).isCloseTo(0.25, within(0.001));
        assertThat(((Number) k2.get("precision")).doubleValue()).isCloseTo(0.25, within(0.001));
        assertThat(((Number) k2.get("f1")).doubleValue()).isCloseTo(0.25, within(0.001));
        assertThat(report.toFormattedSummary()).contains("snapshot-only");
    }

    @Test
    void shouldEvaluateFromJsonFileWithRuntimeProfileAndCategoryMetrics() throws Exception {
        Path dataset = tempDir.resolve("dataset.json");
        Files.writeString(
                dataset,
                "[" +
                        "{\"question\":\"q1\",\"relevantDocIds\":[\"doc-1\",\"doc-2\"],\"category\":\"faq\"}," +
                        "{\"question\":\"q2\",\"relevantDocIds\":[\"doc-9\"],\"category\":\"policy\"}" +
                        "]",
                StandardCharsets.UTF_8
        );

        when(multiRecallService.search(eq("q1"), any(MultiRecallService.SearchOptions.class)))
                .thenReturn(List.of(chunk("doc-1")));
        when(multiRecallService.search(eq("q2"), any(MultiRecallService.SearchOptions.class)))
                .thenReturn(List.of(chunk("other")));

        RagEvaluationService.EvaluationReport report = ragEvaluationService.evaluateFromFile(
                dataset.toString(),
                List.of(3, 3),
                0.61,
                false
        );

        assertThat(report.getDatasetSource())
                .startsWith("dataset.json#")
                .doesNotContain(tempDir.toString());
        assertThat(report.getDatasetSize()).isEqualTo(2);
        assertThat(report.getTopKs()).containsExactly(3);
        assertThat(report.getConfigSnapshot())
                .containsEntry("profile", "runtime")
                .containsEntry("similarityThreshold", 0.61)
                .containsEntry("hybridSearch", false);
        assertThat(report.getCategoryMetrics()).containsKeys("faq", "policy");
        assertThat(report.getCategoryMetrics().get("faq").get("3"))
                .containsEntry("sampleCount", 1)
                .containsKey("p95Latency");

        ArgumentCaptor<MultiRecallService.SearchOptions> optionsCaptor = ArgumentCaptor.forClass(MultiRecallService.SearchOptions.class);
        verify(multiRecallService, times(2)).search(anyString(), optionsCaptor.capture());
        assertThat(optionsCaptor.getAllValues())
                .allSatisfy(options -> {
                    assertThat(options.getTopK()).isEqualTo(3);
                    assertThat(options.getSimilarityThreshold()).isEqualTo(0.61);
                    assertThat(options.isHybridSearch()).isFalse();
                    assertThat(options.isCacheEnabled()).isFalse();
                });
    }

    @Test
    void shouldRejectDatasetOutsideConfiguredDirectory() throws Exception {
        Path allowedDirectory = Files.createDirectory(tempDir.resolve("allowed"));
        Path dataset = tempDir.resolve("outside.json");
        Files.writeString(dataset, "[]", StandardCharsets.UTF_8);
        ReflectionTestUtils.setField(ragEvaluationService, "datasetDirectory", allowedDirectory.toString());

        assertThatThrownBy(() -> ragEvaluationService.evaluateFromFile(dataset.toString(), List.of(1), null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Dataset path is outside the configured directory");
    }

    @Test
    void shouldRejectDatasetSymlinkOutsideConfiguredDirectory() throws Exception {
        Path allowedDirectory = Files.createDirectory(tempDir.resolve("allowed"));
        Path outsideDataset = tempDir.resolve("outside.json");
        Files.writeString(outsideDataset, "[]", StandardCharsets.UTF_8);
        Path linkedDataset = allowedDirectory.resolve("linked.json");
        try {
            Files.createSymbolicLink(linkedDataset, outsideDataset);
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            Assumptions.assumeTrue(false, "Symbolic links are unavailable in this test environment");
        }
        ReflectionTestUtils.setField(ragEvaluationService, "datasetDirectory", allowedDirectory.toString());

        assertThatThrownBy(() -> ragEvaluationService.evaluateFromFile(linkedDataset.toString(), List.of(1), null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Dataset path is outside the configured directory");
    }

    @Test
    void shouldCompareProfilesFromCsvFile() throws Exception {
        Path dataset = tempDir.resolve("dataset.csv");
        Files.writeString(
                dataset,
                "question,relevant_doc_ids,category\n" +
                        "refund process,doc-1,process\n" +
                        "shipping time,doc-2,fact\n",
                StandardCharsets.UTF_8
        );

        when(multiRecallService.search(anyString(), any(MultiRecallService.SearchOptions.class)))
                .thenAnswer(invocation -> {
                    String question = invocation.getArgument(0);
                    MultiRecallService.SearchOptions options = invocation.getArgument(1);
                    if (options.isHybridSearch()) {
                        return question.equals("refund process") ? List.of(chunk("doc-1")) : List.of(chunk("doc-2"));
                    }
                    return question.equals("refund process") ? List.of(chunk("doc-1")) : List.of(chunk("other"));
                });

        RagEvaluationService.ComparisonReport report = ragEvaluationService.compareFromFile(
                dataset.toString(),
                List.of(1, 0),
                List.of(
                        RagEvaluationService.EvaluationProfile.builder()
                                .name("hybrid")
                                .similarityThreshold(0.70)
                                .hybridSearch(true)
                                .build(),
                        RagEvaluationService.EvaluationProfile.builder()
                                .name("vector")
                                .similarityThreshold(0.55)
                                .hybridSearch(false)
                                .build()
                )
        );

        assertThat(report.getDatasetSource())
                .startsWith("dataset.csv#")
                .doesNotContain(tempDir.toString());
        assertThat(report.getDatasetSize()).isEqualTo(2);
        assertThat(report.getTopKs()).containsExactly(1);
        assertThat(report.getReports()).containsKeys("hybrid", "vector");
        assertThat(report.getReports().get("hybrid").getConfigSnapshot())
                .containsEntry("profile", "hybrid")
                .containsEntry("hybridSearch", true);
        assertThat(report.getReports().get("vector").getConfigSnapshot())
                .containsEntry("profile", "vector")
                .containsEntry("hybridSearch", false);
        assertThat(((Number) report.getReports().get("hybrid").getMetrics().get("1").get("recall")).doubleValue())
                .isGreaterThan(((Number) report.getReports().get("vector").getMetrics().get("1").get("recall")).doubleValue());
        assertThat(report.toFormattedSummary()).contains("RAG Comparison Summary");
    }

    @Test
    void shouldRejectEvaluationCaseWithoutGroundTruth() {
        Map<String, List<String>> dataset = Map.of("question", List.of());

        assertThatThrownBy(() -> ragEvaluationService.evaluate(dataset, List.of(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Every evaluation case must contain at least one relevantDocId");
    }

    @Test
    void shouldQuickEvaluateUsingBuiltInDatasetAndFallbackToDefaultTopK() {
        when(multiRecallService.search(anyString(), any(MultiRecallService.SearchOptions.class))).thenReturn(List.of());

        RagEvaluationService.EvaluationReport report = ragEvaluationService.quickEvaluate(List.of(0, -1));

        assertThat(report.getDatasetSize()).isEqualTo(5);
        assertThat(report.getTopKs()).containsExactly(5);
        assertThat(report.getMetrics()).containsKey("5");
        assertThat(report.getMetrics().get("5")).containsKeys(
                "sampleCount", "recall", "precision", "f1", "avgLatency", "p50Latency", "p95Latency", "p99Latency",
                "emptyResultCount", "emptyResultRate", "retrievalHitRate");
    }

    private static RetrievalChunk chunk(String id) {
        return RetrievalChunk.builder().id(id).content(id).build();
    }
}
