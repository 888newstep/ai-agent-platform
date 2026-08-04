package com.aiagent.controller;

import com.aiagent.ecommerce.EcommerceKnowledgeImportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EcommerceImportControllerTest {
    @Mock private EcommerceKnowledgeImportService importService;
    private Executor testExecutor = Runnable::run;
    private EcommerceImportController controller;

    @BeforeEach void setUp() { controller = new EcommerceImportController(importService, testExecutor); }

    @Test void shouldRejectEmptyFilePath() {
        ResponseEntity<Map<String, Object>> resp = controller.importKnowledge(Map.of("filePath", ""));
        assertEquals(400, resp.getStatusCode().value());
    }
    @Test void shouldRejectNullFilePath() {
        ResponseEntity<Map<String, Object>> resp = controller.importKnowledge(Map.of());
        assertEquals(400, resp.getStatusCode().value());
    }
    @Test void shouldStartImport() {
        ResponseEntity<Map<String, Object>> resp = controller.importKnowledge(Map.of("filePath", "/tmp/test.jsonl"));
        assertEquals(200, resp.getStatusCode().value());
        assertEquals("started", resp.getBody().get("status"));
    }
    @Test void shouldRejectTestImportEmptyPath() {
        ResponseEntity<Map<String, Object>> resp = controller.testImport(Map.of("filePath", ""));
        assertEquals(400, resp.getStatusCode().value());
    }
}
