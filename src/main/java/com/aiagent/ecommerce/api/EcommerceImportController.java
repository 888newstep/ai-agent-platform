package com.aiagent.ecommerce.api;

import com.aiagent.ecommerce.application.EcommerceKnowledgeImportService;
import com.aiagent.customer_support.config.CsProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.Executor;

@Slf4j
@RestController
@RequestMapping("/api/v1/ecommerce")
@RequiredArgsConstructor
public class EcommerceImportController {

    private final EcommerceKnowledgeImportService importService;
    private final Executor taskExecutor;
    private final CsProperties csProperties;

    /**
     * 导入路径沙箱：拒绝目录穿越，清洗控制字符（静态分析可证明的净化），
     * 配置了 cs.import.data-dir 时强制限定在数据目录内。
     */
    private String sanitizeFilePath(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("filePath cannot be empty");
        }
        if (raw.contains("..")) {
            throw new IllegalArgumentException("path traversal is not allowed");
        }
        String cleaned = raw.replaceAll("[\\r\\n\\u0000]", "_");
        String dataDir = csProperties.getDataDir();
        if (dataDir != null && !dataDir.isBlank()) {
            Path base = Paths.get(dataDir).toAbsolutePath().normalize();
            Path resolved = Paths.get(cleaned).toAbsolutePath().normalize();
            if (!resolved.startsWith(base)) {
                throw new IllegalArgumentException("filePath must be inside the configured data directory");
            }
            return resolved.toString();
        }
        return cleaned;
    }

    @PostMapping("/import")
    public ResponseEntity<Map<String, Object>> importKnowledge(@RequestBody Map<String, String> request) {
        String filePath;
        try {
            filePath = sanitizeFilePath(request.get("filePath"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }

        log.info("Received import request: {}", filePath);

        taskExecutor.execute(() -> {
            try {
                importService.importFromFile(filePath);
            } catch (Exception e) {
                log.error("Import failed: {}", filePath, e);
            }
        });

        return ResponseEntity.ok(Map.of(
                "status", "started",
                "filePath", filePath,
                "message", "Import task started, check logs for progress"
        ));
    }

    @PostMapping("/import/test")
    public ResponseEntity<Map<String, Object>> testImport(@RequestBody Map<String, String> request) {
        String filePath;
        try {
            filePath = sanitizeFilePath(request.get("filePath"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
        int limit = Integer.parseInt(request.getOrDefault("limit", "5"));

        try {
            var allRecords = importService.parseJsonl(filePath);
            var preview = allRecords.size() > limit
                    ? allRecords.subList(0, limit)
                    : allRecords;

            log.info("Test read: {} records (total {})", preview.size(), allRecords.size());

            return ResponseEntity.ok(Map.of(
                    "totalRecords", allRecords.size(),
                    "previewCount", preview.size(),
                    "preview", preview.stream().map(r -> Map.of(
                            "question", r.getQuestion(),
                            "answer", r.getAnswer(),
                            "qaTextLength", r.getQaText().length()
                    )).toList()
            ));

        } catch (Exception e) {
            log.error("Test read failed", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }
}
