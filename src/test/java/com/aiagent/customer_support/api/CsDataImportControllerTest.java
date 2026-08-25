package com.aiagent.customer_support.api;

import com.aiagent.customer_support.application.CsDataImportService;
import com.aiagent.knowledge.infrastructure.vectorstore.MilvusAdminService;
import com.aiagent.customer_support.config.CsProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CsDataImportControllerTest {
    @Mock private CsDataImportService csDataImportService;
    @Mock private MilvusAdminService milvusAdminService;
    @Mock private CsProperties csProperties;
    private CsDataImportController controller;

    @TempDir
    Path tempDir;

    @BeforeEach void setUp() throws Exception {
        Files.writeString(tempDir.resolve("test.jsonl"), "{}\n");
        Files.writeString(tempDir.resolve("train_clean_v2_small.jsonl"), "{}\n");
        Files.writeString(tempDir.resolve("dev_clean_v2.jsonl"), "{}\n");
        Files.writeString(tempDir.resolve("test_clean_v2.jsonl"), "{}\n");
        org.mockito.Mockito.lenient().when(csProperties.getDataDir()).thenReturn(tempDir.toString());
        org.mockito.Mockito.lenient().when(csDataImportService.importFromJsonl(anyString()))
                .thenReturn(new CsDataImportService.ImportResult());
        org.mockito.Mockito.lenient().when(milvusAdminService.buildAllIndexesAsync())
                .thenReturn(CompletableFuture.completedFuture(null));
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
        assertTrue(((java.util.List<?>) resp.get("files")).contains("test.jsonl"));
    }

    @Test void shouldRejectPathTraversal() {
        assertThrows(IllegalArgumentException.class, () -> controller.importData("../outside.jsonl"));
    }
}
