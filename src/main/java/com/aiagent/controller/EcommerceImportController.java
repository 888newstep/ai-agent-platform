package com.aiagent.controller;

import com.aiagent.ecommerce.EcommerceKnowledgeImportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.Executor;

@Slf4j
@RestController
@RequestMapping("/api/v1/ecommerce")
@RequiredArgsConstructor
public class EcommerceImportController {

    private final EcommerceKnowledgeImportService importService;
    private final Executor taskExecutor;

    @PostMapping("/import")
    public ResponseEntity<Map<String, Object>> importKnowledge(@RequestBody Map<String, String> request) {
        String filePath = request.get("filePath");
        if (filePath == null || filePath.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "filePath cannot be empty"));
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
        String filePath = request.get("filePath");
        int limit = Integer.parseInt(request.getOrDefault("limit", "5"));

        if (filePath == null || filePath.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "filePath cannot be empty"));
        }

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
