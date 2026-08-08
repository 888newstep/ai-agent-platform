package com.aiagent.agent.api;

import com.aiagent.agent.application.AiAgentService;
import com.aiagent.agent.application.MultiAgentService;
import com.aiagent.infrastructure.cache.SemanticCacheService;
import com.aiagent.knowledge.application.DocumentService;
import com.aiagent.knowledge.domain.Document;
import com.aiagent.knowledge.domain.DocumentProcessingStatus;
import com.aiagent.knowledge.domain.RetrievalChunk;
import com.aiagent.rag.application.AdaptiveRagContext;
import com.aiagent.rag.application.RagEvaluationService;
import com.aiagent.rag.application.RagRouteType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiAgentControllerTest {

    @Mock
    private AiAgentService aiAgentService;

    @Mock
    private DocumentService documentService;

    @Mock
    private SemanticCacheService semanticCacheService;

    @Mock
    private MultiAgentService multiAgentService;

    @Mock
    private RagEvaluationService ragEvaluationService;

    private AiAgentController controller;

    @BeforeEach
    void setUp() {
        controller = new AiAgentController(
                aiAgentService,
                documentService,
                semanticCacheService,
                multiAgentService,
                ragEvaluationService
        );
    }

    @Test
    void shouldHandleSessionAndChatOperations() {
        when(aiAgentService.createSession()).thenReturn("session-1");
        when(aiAgentService.chat("session-1", "hello", true)).thenReturn("normal answer");
        when(aiAgentService.reactChat("session-1", "analyze task", false)).thenReturn("react answer");
        when(multiAgentService.execute("plan task", "context")).thenReturn("multi-agent result");
        when(aiAgentService.streamChat("session-1", "stream question", true)).thenReturn(Flux.just("A", "B"));

        ResponseEntity<Map<String, String>> session = controller.createSession();
        controller.clearSession("session-1");
        ResponseEntity<Map<String, Object>> chat = controller.chat("session-1", "hello", true);
        ResponseEntity<Map<String, Object>> react = controller.reactChat("session-1", "analyze task", false);
        ResponseEntity<Map<String, Object>> multiAgent = controller.multiAgentExecute("plan task", "context");
        List<String> stream = controller.streamChat("session-1", "stream question", true).collectList().block();

        assertThat(session.getBody()).containsEntry("sessionId", "session-1");
        assertThat(chat.getBody())
                .containsEntry("answer", "normal answer")
                .containsEntry("mode", "normal");
        assertThat(react.getBody())
                .containsEntry("answer", "react answer")
                .containsEntry("mode", "react");
        assertThat(multiAgent.getBody())
                .containsEntry("answer", "multi-agent result")
                .containsEntry("mode", "multi-agent");
        assertThat(stream).containsExactly("A", "B");
        verify(aiAgentService).clearSession("session-1");
    }

    @Test
    void shouldHandleDocumentAndCacheOperations() {
        MockMultipartFile file = new MockMultipartFile("file", "guide.md", "text/markdown", "hello".getBytes());
        Document document = Document.builder()
                .id(7L)
                .fileName("guide.md")
                .processingStatus(DocumentProcessingStatus.PENDING)
                .build();
        when(documentService.uploadDocument(file)).thenReturn(document);
        when(documentService.getDocumentStatus(7L)).thenReturn(Map.of("documentId", 7L, "status", DocumentProcessingStatus.COMPLETED));
        when(documentService.searchSimilar("refund", 3, 0.8)).thenReturn(List.of(
                RetrievalChunk.builder().id("doc-1").content("refund flow").build()
        ));

        ResponseEntity<Map<String, Object>> upload = controller.uploadDocument(file);
        ResponseEntity<Map<String, Object>> status = controller.getDocumentStatus(7L);
        ResponseEntity<Map<String, Object>> search = controller.searchDocuments("refund", 3, 0.8);
        ResponseEntity<Map<String, String>> clearCache = controller.clearCache();

        assertThat(upload.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(upload.getBody())
                .containsEntry("documentId", 7L)
                .containsEntry("fileName", "guide.md")
                .containsEntry("status", DocumentProcessingStatus.PENDING);
        assertThat(status.getBody()).containsEntry("documentId", 7L);
        assertThat(search.getBody())
                .containsEntry("query", "refund")
                .containsEntry("count", 1);
        assertThat(clearCache.getBody()).containsEntry("message", "Semantic cache cleared");
        verify(semanticCacheService).clear();
    }

    @Test
    void shouldHandleAdaptiveRagDebugRequest() {
        AdaptiveRagContext adaptiveContext = AdaptiveRagContext.builder()
                .originalQuery("refund question")
                .rewrittenQuery("refund process")
                .routeType(RagRouteType.SINGLE_HOP)
                .usedAdaptive(true)
                .retrievalRounds(1)
                .chunkCount(2)
                .build();
        when(aiAgentService.inspectAdaptiveRag("refund question", true)).thenReturn(adaptiveContext);

        ResponseEntity<AdaptiveRagContext> response = controller.debugAdaptiveRag("refund question", true);

        assertThat(response.getBody()).isSameAs(adaptiveContext);
        assertThat(response.getBody().getRouteType()).isEqualTo(RagRouteType.SINGLE_HOP);
        assertThat(response.getBody().isUsedAdaptive()).isTrue();
    }

    @Test
    void shouldHandleQuickEvaluationResponse() {
        RagEvaluationService.EvaluationReport report = evaluationReport("built-in-sample", 5, List.of(2, 4));
        when(ragEvaluationService.quickEvaluate(List.of(2, 4), null, null)).thenReturn(report);

        ResponseEntity<Map<String, Object>> evaluation = controller.evaluateRag("2,4", null, null, null);

        assertThat(evaluation.getBody())
                .containsEntry("datasetSource", "built-in-sample")
                .containsEntry("datasetSize", 5)
                .containsEntry("topKs", List.of(2, 4))
                .containsKey("summary")
                .containsKey("metrics")
                .containsKey("categoryMetrics")
                .containsKey("config")
                .containsEntry("note", "chunkSize/chunkOverlap are snapshot-only and require re-ingestion for fair comparison");
    }

    @Test
    void shouldHandleDatasetEvaluationAndComparison() {
        RagEvaluationService.EvaluationReport evaluationReport = evaluationReport("dataset.json", 3, List.of(1, 3));
        evaluationReport.getConfigSnapshot().put("hybridSearch", false);
        RagEvaluationService.ComparisonReport comparisonReport = new RagEvaluationService.ComparisonReport();
        comparisonReport.setDatasetSource("dataset.json");
        comparisonReport.setDatasetSize(3);
        comparisonReport.setTopKs(List.of(1, 3));
        comparisonReport.setReports(Map.of("baseline", evaluationReport));

        when(ragEvaluationService.evaluateFromFile("dataset.json", List.of(1, 3), 0.61, false)).thenReturn(evaluationReport);
        when(ragEvaluationService.compareFromFile(eq("dataset.json"), eq(List.of(1, 3)), anyList())).thenReturn(comparisonReport);

        ResponseEntity<Map<String, Object>> evaluation = controller.evaluateRag("1,3", "dataset.json", 0.61, false);
        ResponseEntity<Map<String, Object>> comparison = controller.compareRag(
                "dataset.json",
                "1,3",
                "[{\"name\":\"baseline\",\"similarityThreshold\":0.61,\"hybridSearch\":false}]"
        );

        assertThat(evaluation.getBody())
                .containsEntry("datasetSource", "dataset.json")
                .containsEntry("datasetSize", 3);
        assertThat(comparison.getBody())
                .containsEntry("datasetSource", "dataset.json")
                .containsEntry("datasetSize", 3)
                .containsEntry("topKs", List.of(1, 3))
                .containsKey("reports");
        @SuppressWarnings("unchecked")
        Map<String, Object> reports = (Map<String, Object>) comparison.getBody().get("reports");
        assertThat(reports).containsKey("baseline");
    }

    @Test
    void shouldHandleHealthAndAdminPage() {
        ResponseEntity<Map<String, String>> health = controller.health();

        assertThat(health.getBody())
                .containsEntry("status", "UP")
                .containsEntry("version", "1.0.0");
        assertThat(controller.adminPage()).isEqualTo("redirect:/admin.html");
    }

    private static RagEvaluationService.EvaluationReport evaluationReport(String datasetSource, int datasetSize, List<Integer> topKs) {
        RagEvaluationService.EvaluationReport report = new RagEvaluationService.EvaluationReport();
        report.setDatasetSource(datasetSource);
        report.setDatasetSize(datasetSize);
        report.setTopKs(topKs);
        report.setConfigSnapshot(new java.util.LinkedHashMap<>(Map.of("topK", 5, "profile", "runtime")));
        report.addMetric(topKs.get(0), "recall", 0.5);
        report.addMetric(topKs.get(0), "precision", 0.5);
        report.addMetric(topKs.get(0), "f1", 0.5);
        report.addMetric(topKs.get(0), "avgLatency", 12.0);
        report.addMetric(topKs.get(0), "p99Latency", 20L);
        report.addMetric(topKs.get(0), "p50Latency", 10L);
        report.addCategoryMetric("general", String.valueOf(topKs.get(0)), "recall", 0.5);
        return report;
    }
}