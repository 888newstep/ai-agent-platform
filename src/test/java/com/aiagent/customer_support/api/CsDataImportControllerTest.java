package com.aiagent.customer_support.api;

import com.aiagent.customer_support.application.CsDataImportService;
import com.aiagent.knowledge.infrastructure.vectorstore.MilvusAdminService;
import com.aiagent.customer_support.config.CsProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CsDataImportControllerTest {
    @Mock private CsDataImportService csDataImportService;
    @Mock private MilvusAdminService milvusAdminService;
    @Mock private CsProperties csProperties;
    private CsDataImportController controller;

    @BeforeEach void setUp() throws Exception {
        org.mockito.Mockito.lenient().when(csProperties.getDataDir()).thenReturn("/tmp/test");
        controller = new CsDataImportController(csDataImportService, milvusAdminService, csProperties);
    }

    @Test void shouldStartImport() {
        when(csDataImportService.isRunning()).thenReturn(false);
        Map<String, Object> resp = controller.importData("test.jsonl");
        assertTrue((Boolean) resp.get("success"));
    }
    @Test void shouldRejectImportWhenRunning() {
        when(csDataImportService.isRunning()).thenReturn(true);
        Map<String, Object> resp = controller.importData("test.jsonl");
        assertFalse((Boolean) resp.get("success"));
    }
    @Test void shouldStartImportAll() {
        when(csDataImportService.isRunning()).thenReturn(false);
        Map<String, Object> resp = controller.importAll();
        assertTrue((Boolean) resp.get("success"));
    }
    @Test void shouldRejectImportAllWhenRunning() {
        when(csDataImportService.isRunning()).thenReturn(true);
        Map<String, Object> resp = controller.importAll();
        assertFalse((Boolean) resp.get("success"));
    }
    @Test void shouldGetProgress() {
        when(csDataImportService.getProgress()).thenReturn(50L);
        when(csDataImportService.getTotal()).thenReturn(100L);
        when(csDataImportService.isRunning()).thenReturn(true);
        when(csDataImportService.getSuccessCount()).thenReturn(45L);
        when(csDataImportService.getFailCount()).thenReturn(5L);
        Map<String, Object> resp = controller.getProgress();
        assertTrue((Boolean) resp.get("running"));
    }
    @Test void shouldGetProgressWithZeroTotal() {
        when(csDataImportService.getProgress()).thenReturn(0L);
        when(csDataImportService.getTotal()).thenReturn(0L);
        when(csDataImportService.isRunning()).thenReturn(false);
        when(csDataImportService.getSuccessCount()).thenReturn(0L);
        when(csDataImportService.getFailCount()).thenReturn(0L);
        Map<String, Object> resp = controller.getProgress();
        assertFalse((Boolean) resp.get("running"));
    }
    @Test void shouldFinalize() {
        when(csDataImportService.isRunning()).thenReturn(false);
        Map<String, Object> resp = controller.finalizeImport();
        assertTrue((Boolean) resp.get("success"));
    }
    @Test void shouldRejectFinalizeWhenRunning() {
        when(csDataImportService.isRunning()).thenReturn(true);
        Map<String, Object> resp = controller.finalizeImport();
        assertFalse((Boolean) resp.get("success"));
    }
    @Test void shouldListFiles() {
        Map<String, Object> resp = controller.listFiles();
        assertNotNull(resp.get("dataDir"));
    }
}
