package com.aiagent.agent.api;

import com.aiagent.agent.application.AiAgentService;
import com.aiagent.agent.application.ChatExecutionResult;
import com.aiagent.agent.application.MultiAgentExecutionResult;
import com.aiagent.agent.application.MultiAgentExecutionTrace;
import com.aiagent.agent.application.MultiAgentService;
import com.aiagent.infrastructure.cache.SemanticCacheService;
import com.aiagent.infrastructure.idempotency.PersistentIdempotencyContext;
import com.aiagent.knowledge.application.DocumentService;
import com.aiagent.knowledge.domain.Document;
import com.aiagent.knowledge.domain.DocumentProcessingStatus;
import com.aiagent.knowledge.domain.RetrievalChunk;
import com.aiagent.rag.application.AdaptiveRagContext;
import com.aiagent.rag.application.EvaluationReportHistoryService;
import com.aiagent.rag.application.RagEvaluationService;
import com.aiagent.rag.application.RagRouteType;
import com.aiagent.agent.application.ReActExecutionTrace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.TestingAuthenticationToken;
import reactor.core.publisher.Flux;

import java.nio.file.Files;
import java.nio.file.Path;
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

    private EvaluationReportHistoryService evaluationReportHistoryService;
    private AiAgentController controller;
    private TestingAuthenticationToken authentication;

    @BeforeEach
    void setUp() {
        evaluationReportHistoryService = new EvaluationReportHistoryService("evaluation-reports");
        controller = new AiAgentController(
                aiAgentService,
                documentService,
                semanticCacheService,
                multiAgentService,
                ragEvaluationService,
                evaluationReportHistoryService
        );
        authentication = new TestingAuthenticationToken("user", null, "ROLE_USER");
        authentication.setAuthenticated(true);
    }

    @Test
    void shouldHandleSessionAndChatOperations() {
        when(aiAgentService.createSession("user", PersistentIdempotencyContext.disabled()))
                .thenReturn("session-1");
        when(aiAgentService.chat(
                "user", "session-1", "hello", true, PersistentIdempotencyContext.disabled()))
                .thenReturn("normal answer");
        when(aiAgentService.reactChatDetailed(
                "user", "session-1", "analyze task", false, PersistentIdempotencyContext.disabled()))
                .thenReturn(ChatExecutionResult.builder().answer("react answer").build());
        when(multiAgentService.executeDetailed("plan task", "context"))
                .thenReturn(MultiAgentExecutionResult.builder().answer("multi-agent result").build());
        when(aiAgentService.streamChat("user", "session-1", "stream question", true))
                .thenReturn(Flux.just("A", "B"));

        ResponseEntity<Map<String, String>> session = controller.createSession(null, authentication);
        controller.clearSession("session-1", authentication);
        ResponseEntity<Map<String, Object>> chat = controller.chat("session-1", "hello", true, false, null, authentication);
        ResponseEntity<Map<String, Object>> react = controller.reactChat("session-1", "analyze task", false, false, null, authentication);
        ResponseEntity<Map<String, Object>> multiAgent = controller.multiAgentExecute("plan task", "context", false);
        List<String> stream = controller.streamChat("session-1", "stream question", true, null, authentication).collectList().block();

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
        verify(aiAgentService).clearSession("user", "session-1");
    }

    @Test
    void shouldExposeExplainPayloadForReactAndMultiAgent() {
        ReActExecutionTrace reactTrace = ReActExecutionTrace.builder()
                .question("analyze task")
                .stepCount(1)
                .stopReason("final_answer")
                .completed(true)
                .steps(List.of())
                .build();
        when(aiAgentService.reactChatDetailed(
                "user", "session-1", "analyze task", true, PersistentIdempotencyContext.disabled()))
                .thenReturn(ChatExecutionResult.builder()
                .answer("react explain answer")
                .adaptiveRagContext(AdaptiveRagContext.empty("analyze task"))
                .cacheHit(false)
                .responseSource("adaptive_direct_answer")
                .reactTrace(reactTrace)
                .build());
        when(multiAgentService.executeDetailed("plan task", "context")).thenReturn(MultiAgentExecutionResult.builder()
                .answer("multi explain answer")
                .trace(MultiAgentExecutionTrace.builder()
                        .task("plan task")
                        .subtaskCount(1)
                        .stopReason("completed")
                        .subtasks(List.of("subtask"))
                        .workers(List.of())
                        .build())
                .build());

        ResponseEntity<Map<String, Object>> react = controller.reactChat(
                "session-1", "analyze task", true, true, null, authentication);
        ResponseEntity<Map<String, Object>> multi = controller.multiAgentExecute("plan task", "context", true);

        @SuppressWarnings("unchecked")
        Map<String, Object> reactExplain = (Map<String, Object>) react.getBody().get("explain");
        assertThat(reactExplain).containsKey("reactTrace");
        MultiAgentExecutionTrace multiExplain = (MultiAgentExecutionTrace) multi.getBody().get("explain");
        assertThat(multiExplain.getTask()).isEqualTo("plan task");
        assertThat(multiExplain.getStopReason()).isEqualTo("completed");
    }

    @Test
    void shouldExportEvaluationReportToConfiguredDirectory() throws Exception {
        RagEvaluationService.EvaluationReport report = new RagEvaluationService.EvaluationReport();
        report.setDatasetSource("built-in-sample");
        report.setDatasetSize(1);
        report.setTopKs(List.of(1));
        report.setConfigSnapshot(Map.of("profile", "default"));
        when(ragEvaluationService.quickEvaluate(anyList(), eq(null), eq(null))).thenReturn(report);

        Path reportDirectory = Files.createTempDirectory("evaluation-report-test");
        evaluationReportHistoryService = new EvaluationReportHistoryService(reportDirectory.toString());
        controller = new AiAgentController(
                aiAgentService,
                documentService,
                semanticCacheService,
                multiAgentService,
                ragEvaluationService,
                evaluationReportHistoryService
        );
        ResponseEntity<Map<String, Object>> response = controller.exportEvaluation("1", null, null, null);

        assertThat(response.getBody()).containsEntry("exported", true);
        String fileName = (String) response.getBody().get("fileName");
        assertThat(Files.exists(reportDirectory.resolve(fileName))).isTrue();
        assertThat(response.getBody()).doesNotContainKey("filePath");
        Files.deleteIfExists(reportDirectory.resolve(fileName));
        Files.deleteIfExists(reportDirectory);
    }
    @Test
    @SuppressWarnings("unchecked")
    void shouldListAndCompareEvaluationHistory() throws Exception {
        Path reportDirectory = Files.createTempDirectory("evaluation-history-test");
        EvaluationReportHistoryService historyService = new EvaluationReportHistoryService(reportDirectory.toString());
        Map<String, Object> baseline = Map.of(
                "generatedAt", "2026-08-10T10:00:00Z",
                "datasetSource", "sample.json",
                "datasetSize", 1,
                "topKs", List.of(1),
                "metrics", Map.of("1", Map.of("recall", 0.5, "avgLatency", 100.0)),
                "config", Map.of("profile", "baseline")
        );
        Map<String, Object> candidate = Map.of(
                "generatedAt", "2026-08-10T11:00:00Z",
                "datasetSource", "sample.json",
                "datasetSize", 1,
                "topKs", List.of(1),
                "metrics", Map.of("1", Map.of("recall", 0.75, "avgLatency", 90.0)),
                "config", Map.of("profile", "candidate")
        );
        String baselineFile = historyService.save(baseline).fileName();
        String candidateFile = historyService.save(candidate).fileName();
        controller = new AiAgentController(
                aiAgentService,
                documentService,
                semanticCacheService,
                multiAgentService,
                ragEvaluationService,
                historyService
        );

        ResponseEntity<Map<String, Object>> history = controller.evaluationHistory(10);
        ResponseEntity<Map<String, Object>> comparison = controller.compareEvaluationHistory(baselineFile, candidateFile);

        assertThat(history.getBody()).containsEntry("count", 2);
        assertThat(comparison.getBody()).containsEntry("comparable", true);
        Map<String, Object> deltas = (Map<String, Object>) comparison.getBody().get("metricDeltas");
        Map<String, Object> topKMetrics = (Map<String, Object>) deltas.get("1");
        assertThat((Double) topKMetrics.get("recall")).isEqualTo(0.25);
        assertThat((Double) topKMetrics.get("avgLatency")).isEqualTo(-10.0);
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

    @Test
    void shouldExposeDocumentRetryAndDeleteOperations() {
        Document failed = Document.builder()
                .id(51L)
                .fileName("failed.md")
                .processingStatus(DocumentProcessingStatus.PENDING)
                .build();
        when(documentService.retryDocument(51L)).thenReturn(failed);

        var retryResponse = controller.retryDocument(51L);
        var deleteResponse = controller.deleteDocument(51L);

        assertThat(retryResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(retryResponse.getBody())
                .containsEntry("documentId", 51L)
                .containsEntry("status", DocumentProcessingStatus.PENDING);
        assertThat(deleteResponse.getBody()).containsEntry("documentId", 51L);
        verify(documentService).deleteDocument(51L);
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
