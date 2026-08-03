package com.aiagent.controller;

import com.aiagent.agent.AiAgentService;
import com.aiagent.cache.SemanticCacheService;
import com.aiagent.document.DocumentChunk;
import com.aiagent.document.DocumentService;
import com.aiagent.entity.Document;
import com.aiagent.entity.DocumentProcessingStatus;
import com.aiagent.evaluation.RagEvaluationService;
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
    private com.aiagent.agent.MultiAgentService multiAgentService;

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
        when(aiAgentService.chat("session-1", "你好", true)).thenReturn("普通回答");
        when(aiAgentService.reactChat("session-1", "分析一下", false)).thenReturn("ReAct回答");
        when(multiAgentService.execute("规划任务", "上下文")).thenReturn("多智能体结果");
        when(aiAgentService.streamChat("session-1", "流式问题", true)).thenReturn(Flux.just("A", "B"));

        ResponseEntity<Map<String, String>> session = controller.createSession();
        controller.clearSession("session-1");
        ResponseEntity<Map<String, Object>> chat = controller.chat("session-1", "你好", true);
        ResponseEntity<Map<String, Object>> react = controller.reactChat("session-1", "分析一下", false);
        ResponseEntity<Map<String, Object>> multiAgent = controller.multiAgentExecute("规划任务", "上下文");
        List<String> stream = controller.streamChat("session-1", "流式问题", true).collectList().block();

        assertThat(session.getBody()).containsEntry("sessionId", "session-1");
        assertThat(chat.getBody())
                .containsEntry("answer", "普通回答")
                .containsEntry("mode", "normal");
        assertThat(react.getBody())
                .containsEntry("answer", "ReAct回答")
                .containsEntry("mode", "react");
        assertThat(multiAgent.getBody())
                .containsEntry("answer", "多智能体结果")
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
        when(documentService.searchSimilar("退款", 3, 0.8)).thenReturn(List.of(
                DocumentChunk.builder().id("doc-1").content("退款流程").build()
        ));

        ResponseEntity<Map<String, Object>> upload = controller.uploadDocument(file);
        ResponseEntity<Map<String, Object>> status = controller.getDocumentStatus(7L);
        ResponseEntity<Map<String, Object>> search = controller.searchDocuments("退款", 3, 0.8);
        ResponseEntity<Map<String, String>> clearCache = controller.clearCache();

        assertThat(upload.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(upload.getBody())
                .containsEntry("documentId", 7L)
                .containsEntry("fileName", "guide.md")
                .containsEntry("status", DocumentProcessingStatus.PENDING);
        assertThat(status.getBody()).containsEntry("documentId", 7L);
        assertThat(search.getBody())
                .containsEntry("query", "退款")
                .containsEntry("count", 1);
        assertThat(clearCache.getBody()).containsEntry("message", "Semantic cache cleared");
        verify(semanticCacheService).clear();
    }

    @Test
    void shouldHandleEvaluationHealthAndAdminPage() {
        RagEvaluationService.EvaluationReport report = new RagEvaluationService.EvaluationReport();
        report.setDatasetSize(5);
        report.setConfigSnapshot(Map.of("topK", 5));
        report.addMetric(2, "recall", 0.5);
        report.addMetric(2, "precision", 0.5);
        report.addMetric(2, "f1", 0.5);
        report.addMetric(2, "avgLatency", 12.0);
        report.addMetric(2, "p99Latency", 20L);
        when(ragEvaluationService.quickEvaluate(List.of(2, 4))).thenReturn(report);

        ResponseEntity<Map<String, Object>> evaluation = controller.evaluateRag("2,4");
        ResponseEntity<Map<String, String>> health = controller.health();

        assertThat(evaluation.getBody())
                .containsEntry("datasetSize", 5)
                .containsKey("summary")
                .containsKey("metrics")
                .containsKey("config");
        assertThat(health.getBody())
                .containsEntry("status", "UP")
                .containsEntry("version", "1.0.0");
        assertThat(controller.adminPage()).isEqualTo("redirect:/admin.html");
    }
}
