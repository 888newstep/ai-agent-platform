package com.aiagent.ecommerce.api;

import com.aiagent.ecommerce.application.EcommerceDataGeneratorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import java.util.Map;
import java.util.concurrent.Executor;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class EcommerceDataGeneratorControllerTest {
    @Mock private EcommerceDataGeneratorService generatorService;
    private Executor testExecutor = r -> r.run();
    private EcommerceDataGeneratorController controller;

    @BeforeEach void setUp() { controller = new EcommerceDataGeneratorController(generatorService, testExecutor); }

    @Test void shouldRunGenerator() {
        ResponseEntity<Map<String, Object>> resp = controller.runGenerator();
        assertEquals(200, resp.getStatusCode().value());
        assertEquals("started", resp.getBody().get("status"));
    }
    @Test void shouldGenerateFaq() {
        ResponseEntity<Map<String, Object>> resp = controller.generateFaq();
        assertEquals(200, resp.getStatusCode().value());
        assertEquals("FAQ", resp.getBody().get("format"));
    }
    @Test void shouldGenerateConversations() {
        ResponseEntity<Map<String, Object>> resp = controller.generateConversations();
        assertEquals(200, resp.getStatusCode().value());
    }
    @Test void shouldGenerateArticles() {
        ResponseEntity<Map<String, Object>> resp = controller.generateArticles();
        assertEquals(200, resp.getStatusCode().value());
    }
    @Test void shouldGenerateCsv() {
        ResponseEntity<Map<String, Object>> resp = controller.generateCsv();
        assertEquals(200, resp.getStatusCode().value());
    }
    @Test void shouldGenerateJsonl() {
        ResponseEntity<Map<String, Object>> resp = controller.generateJsonl();
        assertEquals(200, resp.getStatusCode().value());
    }
    @Test void shouldPreview() {
        ResponseEntity<Map<String, Object>> resp = controller.preview();
        assertEquals(200, resp.getStatusCode().value());
        assertNotNull(resp.getBody().get("formats"));
    }
    @Test void shouldGetStats() {
        ResponseEntity<Map<String, Object>> resp = controller.getStats();
        assertEquals(200, resp.getStatusCode().value());
    }
}
