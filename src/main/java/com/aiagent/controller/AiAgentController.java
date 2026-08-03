package com.aiagent.controller;

import com.aiagent.agent.AiAgentService;
import com.aiagent.cache.SemanticCacheService;
import com.aiagent.document.DocumentService;
import com.aiagent.evaluation.RagEvaluationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/agent")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class AiAgentController {

    private final AiAgentService aiAgentService;
    private final DocumentService documentService;
    private final SemanticCacheService semanticCacheService;
    private final com.aiagent.agent.MultiAgentService multiAgentService;
    private final RagEvaluationService ragEvaluationService;

    @PostMapping("/session")
    public ResponseEntity<Map<String, String>> createSession() {
        String sessionId = aiAgentService.createSession();
        Map<String, String> response = new HashMap<>();
        response.put("sessionId", sessionId);
        response.put("message", "Session created");
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/session/{sessionId}")
    public ResponseEntity<Void> clearSession(@PathVariable String sessionId) {
        aiAgentService.clearSession(sessionId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/chat")
    public ResponseEntity<Map<String, Object>> chat(
            @RequestParam String sessionId,
            @RequestParam String question,
            @RequestParam(defaultValue = "true") boolean useRag) {
        String response = aiAgentService.chat(sessionId, question, useRag);
        Map<String, Object> result = new HashMap<>();
        result.put("sessionId", sessionId);
        result.put("question", question);
        result.put("answer", response);
        result.put("mode", "normal");
        return ResponseEntity.ok(result);
    }

    @PostMapping("/react/chat")
    public ResponseEntity<Map<String, Object>> reactChat(
            @RequestParam String sessionId,
            @RequestParam String question,
            @RequestParam(defaultValue = "true") boolean useRag) {
        String response = aiAgentService.reactChat(sessionId, question, useRag);
        Map<String, Object> result = new HashMap<>();
        result.put("sessionId", sessionId);
        result.put("question", question);
        result.put("answer", response);
        result.put("mode", "react");
        return ResponseEntity.ok(result);
    }

    @PostMapping("/multi-agent/execute")
    public ResponseEntity<Map<String, Object>> multiAgentExecute(
            @RequestParam String task,
            @RequestParam(defaultValue = "") String context) {
        String response = multiAgentService.execute(task, context);
        Map<String, Object> result = new HashMap<>();
        result.put("task", task);
        result.put("answer", response);
        result.put("mode", "multi-agent");
        return ResponseEntity.ok(result);
    }

    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> streamChat(
            @RequestParam String sessionId,
            @RequestParam String question,
            @RequestParam(defaultValue = "true") boolean useRag) {
        return aiAgentService.streamChat(sessionId, question, useRag);
    }

    @PostMapping("/document/upload")
    public ResponseEntity<Map<String, Object>> uploadDocument(@RequestParam("file") MultipartFile file) {
        var document = documentService.uploadDocument(file);
        Map<String, Object> response = new HashMap<>();
        response.put("message", "Document accepted and queued for async ingestion");
        response.put("documentId", document.getId());
        response.put("fileName", document.getFileName());
        response.put("status", document.getProcessingStatus());
        return ResponseEntity.accepted().body(response);
    }

    @GetMapping("/document/{documentId}/status")
    public ResponseEntity<Map<String, Object>> getDocumentStatus(@PathVariable Long documentId) {
        return ResponseEntity.ok(documentService.getDocumentStatus(documentId));
    }

    @PostMapping("/document/search")
    public ResponseEntity<Map<String, Object>> searchDocuments(
            @RequestParam String query,
            @RequestParam(defaultValue = "5") int topK,
            @RequestParam(defaultValue = "0.7") double threshold) {
        var chunks = documentService.searchSimilar(query, topK, threshold);
        Map<String, Object> response = new HashMap<>();
        response.put("query", query);
        response.put("results", chunks);
        response.put("count", chunks.size());
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/cache")
    public ResponseEntity<Map<String, String>> clearCache() {
        semanticCacheService.clear();
        Map<String, String> response = new HashMap<>();
        response.put("message", "Semantic cache cleared");
        return ResponseEntity.ok(response);
    }

    @PostMapping("/evaluate")
    public ResponseEntity<Map<String, Object>> evaluateRag(
            @RequestParam(defaultValue = "1,3,5,10") String topKs) {
        List<Integer> kValues = java.util.Arrays.stream(topKs.split(","))
                .map(String::trim)
                .map(Integer::parseInt)
                .toList();
        var report = ragEvaluationService.quickEvaluate(kValues);
        Map<String, Object> response = new HashMap<>();
        response.put("summary", report.toFormattedSummary());
        response.put("metrics", report.getMetrics());
        response.put("datasetSize", report.getDatasetSize());
        response.put("config", report.getConfigSnapshot());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        Map<String, String> response = new HashMap<>();
        response.put("status", "UP");
        response.put("service", "AI Customer Service Agent");
        response.put("version", "1.0.0");
        return ResponseEntity.ok(response);
    }

    @GetMapping({"/", "/admin"})
    public String adminPage() {
        return "redirect:/admin.html";
    }
}
