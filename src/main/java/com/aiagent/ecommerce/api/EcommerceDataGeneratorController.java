package com.aiagent.ecommerce.api;

import com.aiagent.ecommerce.application.EcommerceDataGeneratorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;

@Slf4j
@RestController
@RequestMapping("/api/v1/ecommerce/generator")
@RequiredArgsConstructor
public class EcommerceDataGeneratorController {

    private final EcommerceDataGeneratorService generatorService;
    private final Executor taskExecutor;

    @PostMapping("/run")
    public ResponseEntity<Map<String, Object>> runGenerator() {
        log.info("Received full data generation request");

        taskExecutor.execute(() -> {
            try {
                var summary = generatorService.generateAll();
                log.info("\n{}", summary.format());
            } catch (Exception e) {
                log.error("Full data generation failed", e);
            }
        });

        return ResponseEntity.ok(Map.of(
                "status", "started",
                "model", "doubao-seed-2-0-mini",
                "outputDir", "generated_data/",
                "message", "Full generation task started, check logs for progress"
        ));
    }

    @PostMapping("/faq")
    public ResponseEntity<Map<String, Object>> generateFaq() {
        return runSingleFormat("FAQ", generatorService::generateFaq);
    }

    @PostMapping("/conversations")
    public ResponseEntity<Map<String, Object>> generateConversations() {
        return runSingleFormat("Conversations", generatorService::generateConversations);
    }

    @PostMapping("/articles")
    public ResponseEntity<Map<String, Object>> generateArticles() {
        return runSingleFormat("Articles", generatorService::generateArticles);
    }

    @PostMapping("/csv")
    public ResponseEntity<Map<String, Object>> generateCsv() {
        return runSingleFormat("CSV", generatorService::generateCsv);
    }

    @PostMapping("/jsonl")
    public ResponseEntity<Map<String, Object>> generateJsonl() {
        return runSingleFormat("JSONL", generatorService::generateJsonl);
    }

    @GetMapping("/preview")
    public ResponseEntity<Map<String, Object>> preview() {
        Map<String, Object> formats = new LinkedHashMap<>();
        formats.put("faq_knowledge.txt", "FAQ format (Q/A pairs), ~2000-2400 entries");
        formats.put("conversations.txt", "Multi-turn dialogue format, ~800-900 segments");
        formats.put("knowledge_articles.txt", "Knowledge articles, ~500-600 articles");
        formats.put("structured_data.csv", "CSV structured format, ~2500-3000 rows");
        formats.put("qa_pairs.jsonl", "Standard JSONL format, ~2000-2400 entries");

        return ResponseEntity.ok(Map.of(
                "model", "doubao-seed-2-0-mini",
                "outputDir", "generated_data/",
                "formats", formats
        ));
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        return ResponseEntity.ok(Map.of(
                "message", "Check application logs for detailed stats",
                "outputDir", "generated_data/"
        ));
    }

    private ResponseEntity<Map<String, Object>> runSingleFormat(String formatName, Runnable task) {
        taskExecutor.execute(() -> {
            try {
                task.run();
            } catch (Exception e) {
                log.error("{} generation failed", formatName, e);
            }
        });

        return ResponseEntity.ok(Map.of(
                "status", "started",
                "format", formatName,
                "outputDir", "generated_data/",
                "message", formatName + " generation started, check logs for progress"
        ));
    }
}
