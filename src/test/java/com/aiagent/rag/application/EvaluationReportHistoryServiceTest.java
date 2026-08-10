package com.aiagent.rag.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvaluationReportHistoryServiceTest {

    @TempDir
    Path reportDirectory;

    @Test
    void shouldSaveAndListReportsByNewestModifiedTime() throws Exception {
        EvaluationReportHistoryService service = new EvaluationReportHistoryService(reportDirectory.toString());

        EvaluationReportHistoryService.SavedReport savedReport = service.save(report("2026-08-10T10:00:00Z", 0.5));

        List<Map<String, Object>> reports = service.list(20);

        assertThat(savedReport.fileName()).startsWith("rag-evaluation-").endsWith(".json");
        assertThat(reports).hasSize(1);
        assertThat(reports.get(0))
                .containsEntry("fileName", savedReport.fileName())
                .containsEntry("datasetSource", "sample.json")
                .containsEntry("datasetSize", 2);
        assertThat(reports.get(0)).containsKey("lastModifiedAt");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldCompareMetricsAndReturnCandidateMinusBaseline() throws Exception {
        EvaluationReportHistoryService service = new EvaluationReportHistoryService(reportDirectory.toString());
        EvaluationReportHistoryService.SavedReport baseline = service.save(report("2026-08-10T10:00:00Z", 0.5));
        EvaluationReportHistoryService.SavedReport candidate = service.save(report("2026-08-10T11:00:00Z", 0.75));

        Map<String, Object> comparison = service.compare(baseline.fileName(), candidate.fileName());

        assertThat(comparison).containsEntry("comparable", true);
        Map<String, Object> deltas = (Map<String, Object>) comparison.get("metricDeltas");
        Map<String, Object> topKMetrics = (Map<String, Object>) deltas.get("1");
        assertThat((Double) topKMetrics.get("recall")).isEqualTo(0.25);
        assertThat((Double) topKMetrics.get("avgLatency")).isEqualTo(-10.0);
    }

    @Test
    void shouldRejectPathTraversalAndUnknownReportNames() throws Exception {
        EvaluationReportHistoryService service = new EvaluationReportHistoryService(reportDirectory.toString());

        assertThatThrownBy(() -> service.compare("../secret.json", "rag-evaluation-1234567890123.json"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid evaluation report file name");
        assertThatThrownBy(() -> service.compare("rag-evaluation-1234567890123.json", "rag-evaluation-1234567890123.json"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Evaluation report not found");
    }

    private Map<String, Object> report(String generatedAt, double recall) {
        return Map.of(
                "generatedAt", generatedAt,
                "datasetSource", "sample.json",
                "datasetSize", 2,
                "topKs", List.of(1),
                "metrics", Map.of("1", Map.of(
                        "recall", recall,
                        "precision", recall,
                        "avgLatency", 100.0 - (recall * 40)
                )),
                "config", Map.of("profile", "test")
        );
    }
}