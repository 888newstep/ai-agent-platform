package com.aiagent.controller;

import com.aiagent.agent.AiAgentService;
import com.aiagent.cache.SemanticCacheService;
import com.aiagent.document.DocumentService;
import com.aiagent.evaluation.RagEvaluationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 智能客服 REST API
 *
 * <p>提供会话管理、AI 对话（普通/ReAct/Multi-Agent）、知识库文档管理、
 * 语义缓存管理等功能，支持企业级客服场景的完整工作流。
 */
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

    // ==================== 1. 会话管理 ====================

    /**
     * 创建新会话
     * 客服场景：用户接入时创建一个新的会话上下文
     */
    @PostMapping("/session")
    public ResponseEntity<Map<String, String>> createSession() {
        String sessionId = aiAgentService.createSession();
        Map<String, String> response = new HashMap<>();
        response.put("sessionId", sessionId);
        response.put("message", "客服会话已创建");
        return ResponseEntity.ok(response);
    }

    /**
     * 清除会话上下文
     * 客服场景：用户结束服务时清除会话上下文
     */
    @DeleteMapping("/session/{sessionId}")
    public ResponseEntity<Void> clearSession(@PathVariable String sessionId) {
        aiAgentService.clearSession(sessionId);
        return ResponseEntity.ok().build();
    }

    // ==================== 2. AI 对话（三种模式） ====================

    /**
     * 普通对话模式
     * 客服场景：知识库问答、常见问题解答
     */
    @PostMapping("/chat")
    public ResponseEntity<Map<String, Object>> chat(
            @RequestParam String sessionId,
            @RequestParam String question,
            @RequestParam(defaultValue = "true") boolean useRag) {
        log.info("客服对话: sessionId={}, question={}, useRag={}", sessionId, question, useRag);
        String response = aiAgentService.chat(sessionId, question, useRag);
        Map<String, Object> result = new HashMap<>();
        result.put("sessionId", sessionId);
        result.put("question", question);
        result.put("answer", response);
        result.put("mode", "normal");
        return ResponseEntity.ok(result);
    }

    /**
     * ReAct 推理对话模式
     * 客服场景：需要查询数据库、调用外部 API 的复杂问题
     * 如：查询订单状态、退款进度、物流信息等
     */
    @PostMapping("/react/chat")
    public ResponseEntity<Map<String, Object>> reactChat(
            @RequestParam String sessionId,
            @RequestParam String question,
            @RequestParam(defaultValue = "true") boolean useRag) {
        log.info("客服 ReAct 推理: sessionId={}, question={}, useRag={}", sessionId, question, useRag);
        String response = aiAgentService.reactChat(sessionId, question, useRag);
        Map<String, Object> result = new HashMap<>();
        result.put("sessionId", sessionId);
        result.put("question", question);
        result.put("answer", response);
        result.put("mode", "react");
        return ResponseEntity.ok(result);
    }

    /**
     * Multi-Agent 协作模式
     * 客服场景：需要多维度分析、多步骤处理的复杂任务
     * 如：投诉处理（客服+售后+技术 多角色协作）
     */
    @PostMapping("/multi-agent/execute")
    public ResponseEntity<Map<String, Object>> multiAgentExecute(
            @RequestParam String task,
            @RequestParam(defaultValue = "") String context) {
        log.info("客服 Multi-Agent 协作: task={}", task);
        String response = multiAgentService.execute(task, context);
        Map<String, Object> result = new HashMap<>();
        result.put("task", task);
        result.put("answer", response);
        result.put("mode", "multi-agent");
        return ResponseEntity.ok(result);
    }

    /**
     * 流式对话（SSE）
     * 客服场景：实时输出回答，提升用户体验
     */
    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> streamChat(
            @RequestParam String sessionId,
            @RequestParam String question,
            @RequestParam(defaultValue = "true") boolean useRag) {
        return aiAgentService.streamChat(sessionId, question, useRag);
    }

    // ==================== 3. 知识库管理 ====================

    /**
     * 上传文档到知识库
     * 客服场景：导入产品手册、FAQ、政策文档等
     */
    @PostMapping("/document/upload")
    public ResponseEntity<Map<String, String>> uploadDocument(@RequestParam("file") MultipartFile file) {
        documentService.uploadDocument(file);
        Map<String, String> response = new HashMap<>();
        response.put("message", "文档上传成功，已加入知识库");
        response.put("fileName", file.getOriginalFilename());
        return ResponseEntity.ok(response);
    }

    /**
     * 搜索知识库
     * 客服场景：根据用户问题检索相关文档片段
     */
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

    // ==================== 4. 缓存管理 ====================

    /**
     * 清空语义缓存
     * 客服场景：知识库更新后清空缓存，确保回答使用最新数据
     */
    @DeleteMapping("/cache")
    public ResponseEntity<Map<String, String>> clearCache() {
        semanticCacheService.clear();
        Map<String, String> response = new HashMap<>();
        response.put("message", "语义缓存已清空");
        return ResponseEntity.ok(response);
    }

    // ==================== 5. RAG 评估 ====================

    /**
     * 触发 RAG 效果评估（Q173）
     *
     * 返回召回率、准确率、F1、延迟（avg/p50/p99）等指标
     * 可用于面试时展示数据驱动优化能力
     *
     * @param topKs 要测试的 k 值列表（默认 1,3,5,10）
     */
    @PostMapping("/evaluate")
    public ResponseEntity<Map<String, Object>> evaluateRag(
            @RequestParam(defaultValue = "1,3,5,10") String topKs) {
        log.info("触发 RAG 评估: topKs={}", topKs);
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

    // ==================== 6. 系统监控 ====================

    /**
     * 健康检查
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        Map<String, String> response = new HashMap<>();
        response.put("status", "UP");
        response.put("service", "AI Customer Service Agent");
        response.put("version", "1.0.0");
        return ResponseEntity.ok(response);
    }

    // ==================== 6. 页面路由 ====================

    /**
     * 管理后台
     */
    @GetMapping({"/", "/admin"})
    public String adminPage() {
        return "redirect:/admin.html";
    }
}