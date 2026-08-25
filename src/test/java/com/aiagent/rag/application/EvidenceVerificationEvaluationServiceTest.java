package com.aiagent.rag.application;

import com.aiagent.infrastructure.config.AiProperties;
import com.aiagent.knowledge.domain.RetrievalChunk;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvidenceVerificationEvaluationServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldReportPerfectMetricsForCuratedRegressionDataset() {
        EvidenceVerificationEvaluationService service = service((query, chunks) -> chunks);
        ReflectionTestUtils.setField(service, "datasetDirectory", "./examples/evaluation-datasets");

        EvidenceVerificationEvaluationService.EvaluationReport report = service.evaluateFromFile(
                "./examples/evaluation-datasets/evidence-verification-sample.json", false);

        assertThat(report.getSampleCount()).isEqualTo(6);
        assertThat(report.getMetrics().getTruePositive()).isEqualTo(3);
        assertThat(report.getMetrics().getTrueNegative()).isEqualTo(3);
        assertThat(report.getMetrics().getFalsePositive()).isZero();
        assertThat(report.getMetrics().getFalseNegative()).isZero();
        assertThat(report.getMetrics().getPrecision()).isEqualTo(1.0);
        assertThat(report.getMetrics().getFalsePositiveRate()).isZero();
        assertThat(report.getDatasetSource()).startsWith("evidence-verification-sample.json#");
    }

    @Test
    void shouldSupportLiveRerankEvaluation() throws Exception {
        Path dataset = tempDir.resolve("live.json");
        Files.writeString(dataset, """
                [{
                  "question":"退款多久到账？",
                  "keywords":["退款","到账"],
                  "evidence":"退款审核通过后原路到账。",
                  "expectedSupported":true,
                  "category":"refund"
                }]
                """, StandardCharsets.UTF_8);
        EvidenceReranker reranker = (query, chunks) -> chunks.stream()
                .map(chunk -> RetrievalChunk.builder()
                        .id(chunk.getId())
                        .content(chunk.getContent())
                        .metadata(Map.of(SemanticEvidenceReranker.SEMANTIC_SCORE_METADATA, 0.95))
                        .build())
                .toList();
        EvidenceVerificationEvaluationService service = service(reranker);
        ReflectionTestUtils.setField(service, "datasetDirectory", tempDir.toString());

        EvidenceVerificationEvaluationService.EvaluationReport report =
                service.evaluateFromFile(dataset.toString(), true);

        assertThat(report.isLiveRerank()).isTrue();
        assertThat(report.getMetrics().getTruePositive()).isEqualTo(1);
        assertThat(report.getCases()).extracting(
                EvidenceVerificationEvaluationService.CaseResult::isPredictedSupported)
                .containsExactly(true);
    }

    @Test
    void shouldRejectDatasetOutsideConfiguredDirectory() throws Exception {
        Path allowedDirectory = Files.createDirectory(tempDir.resolve("allowed"));
        Path outside = tempDir.resolve("outside.json");
        Files.writeString(outside, "[]", StandardCharsets.UTF_8);
        EvidenceVerificationEvaluationService service = service((query, chunks) -> List.of());
        ReflectionTestUtils.setField(service, "datasetDirectory", allowedDirectory.toString());

        assertThatThrownBy(() -> service.evaluateFromFile(outside.toString(), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside the configured directory");
    }

    private EvidenceVerificationEvaluationService service(EvidenceReranker reranker) {
        return new EvidenceVerificationEvaluationService(
                new ObjectMapper(),
                new SelfRagVerifier(new AiProperties()),
                reranker);
    }
}
