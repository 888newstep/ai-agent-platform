package com.aiagent.controller;

import com.aiagent.ecommerce.EcommerceDataGeneratorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 电商客服多格式训练数据生成接口
 *
 * 生成 5 种格式的训练数据文件，存放在 generated_data/ 目录下：
 *   faq_knowledge.txt       — FAQ 问答格式
 *   conversations.txt       — 多轮对话格式
 *   knowledge_articles.txt  — 知识文章格式
 *   structured_data.csv     — CSV 结构化格式
 *   qa_pairs.jsonl          — 标准 JSONL 格式（兼容原有导入流程）
 *
 * 支持全量生成和按格式独立生成：
 *   全量: POST /api/v1/ecommerce/generator/run
 *   独立: POST /api/v1/ecommerce/generator/faq
 *          POST /api/v1/ecommerce/generator/conversations
 *          POST /api/v1/ecommerce/generator/articles
 *          POST /api/v1/ecommerce/generator/csv
 *          POST /api/v1/ecommerce/generator/jsonl
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/ecommerce/generator")
@RequiredArgsConstructor
public class EcommerceDataGeneratorController {

    private final EcommerceDataGeneratorService generatorService;

    // =============================================
    // 全量生成
    // =============================================

    /**
     * 触发全量生成（5 种格式，8 个类别）
     *
     * POST /api/v1/ecommerce/generator/run
     */
    @PostMapping("/run")
    public ResponseEntity<Map<String, Object>> runGenerator() {
        log.info("收到全量数据生成请求（预算 3.0 元）");

        Map<String, Object> response = new HashMap<>();
        response.put("status", "started");
        response.put("budget", "¥3.0");
        response.put("model", "doubao-seed-2-0-mini");
        response.put("formats", "FAQ / 多轮对话 / 知识文章 / CSV / JSONL");
        response.put("outputDir", "generated_data/");
        response.put("estimatedOutput", "FAQ 2000-2400条 / 对话 800-900段 / 文章 500-600篇 / CSV 2500-3000条 / JSONL 2000-2400条");
        response.put("message", "全量生成任务已启动，请查看日志获取进度。");

        new Thread(() -> {
            try {
                var summary = generatorService.generateAll();
                log.info("\n{}", summary.format());
            } catch (Exception e) {
                log.error("全量数据生成失败", e);
            }
        }, "data-generator-all").start();

        return ResponseEntity.ok(response);
    }

    // =============================================
    // 独立格式生成（5 个独立端点）
    // =============================================

    /**
     * 单独生成 FAQ 格式
     *
     * POST /api/v1/ecommerce/generator/faq
     */
    @PostMapping("/faq")
    public ResponseEntity<Map<String, Object>> generateFaq() {
        log.info("收到 FAQ 格式生成请求");
        return runSingleFormat("FAQ", "faq_knowledge.txt", generatorService::generateFaq);
    }

    /**
     * 单独生成多轮对话格式
     *
     * POST /api/v1/ecommerce/generator/conversations
     */
    @PostMapping("/conversations")
    public ResponseEntity<Map<String, Object>> generateConversations() {
        log.info("收到多轮对话格式生成请求");
        return runSingleFormat("多轮对话", "conversations.txt", generatorService::generateConversations);
    }

    /**
     * 单独生成知识文章格式
     *
     * POST /api/v1/ecommerce/generator/articles
     */
    @PostMapping("/articles")
    public ResponseEntity<Map<String, Object>> generateArticles() {
        log.info("收到知识文章格式生成请求");
        return runSingleFormat("知识文章", "knowledge_articles.txt", generatorService::generateArticles);
    }

    /**
     * 单独生成 CSV 格式
     *
     * POST /api/v1/ecommerce/generator/csv
     */
    @PostMapping("/csv")
    public ResponseEntity<Map<String, Object>> generateCsv() {
        log.info("收到 CSV 格式生成请求");
        return runSingleFormat("CSV", "structured_data.csv", generatorService::generateCsv);
    }

    /**
     * 单独生成 JSONL 格式
     *
     * POST /api/v1/ecommerce/generator/jsonl
     */
    @PostMapping("/jsonl")
    public ResponseEntity<Map<String, Object>> generateJsonl() {
        log.info("收到 JSONL 格式生成请求");
        return runSingleFormat("JSONL", "qa_pairs.jsonl", generatorService::generateJsonl);
    }

    // =============================================
    // 预览 & 状态
    // =============================================

    /**
     * 预算预览
     *
     * GET /api/v1/ecommerce/generator/preview
     */
    @GetMapping("/preview")
    public ResponseEntity<Map<String, Object>> preview() {
        Map<String, Object> preview = new HashMap<>();
        preview.put("model", "doubao-seed-2-0-mini");
        preview.put("budget", "¥3.0");
        preview.put("costPerMillionTokens", "¥0.5");
        preview.put("estimatedTokens", "~6,000,000");

        Map<String, Object> formats = new LinkedHashMap<>();
        formats.put("faq_knowledge.txt", "FAQ 格式（Q:/A: 问答对），20% 预算，预估 2000-2400 条");
        formats.put("conversations.txt", "多轮对话格式（User/Assistant），30% 预算，预估 800-900 段");
        formats.put("knowledge_articles.txt", "知识文章格式（# 标题 + 正文），30% 预算，预估 500-600 篇");
        formats.put("structured_data.csv", "CSV 结构化格式，20% 预算，预估 2500-3000 条");
        formats.put("qa_pairs.jsonl", "标准 JSONL 格式，10% 预算，预估 2000-2400 条");
        preview.put("formats", formats);

        preview.put("outputDir", "generated_data/");
        preview.put("note", "执行 POST /api/v1/ecommerce/generator/run 启动全量生成");
        preview.put("endpoints", Map.of(
                "faq", "POST /api/v1/ecommerce/generator/faq",
                "conversations", "POST /api/v1/ecommerce/generator/conversations",
                "articles", "POST /api/v1/ecommerce/generator/articles",
                "csv", "POST /api/v1/ecommerce/generator/csv",
                "jsonl", "POST /api/v1/ecommerce/generator/jsonl",
                "all", "POST /api/v1/ecommerce/generator/run"
        ));

        return ResponseEntity.ok(preview);
    }

    /**
     * 获取生成状态
     *
     * GET /api/v1/ecommerce/generator/stats
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("message", "详细统计信息请查看应用日志");
        stats.put("hint", "启动生成: POST /api/v1/ecommerce/generator/run");
        stats.put("outputDir", "generated_data/");
        return ResponseEntity.ok(stats);
    }

    // =============================================
    // 内部辅助
    // =============================================

    /**
     * 通用单格式生成启动
     */
    private ResponseEntity<Map<String, Object>> runSingleFormat(String formatName, String fileName, Runnable task) {
        Map<String, Object> response = new HashMap<>();
        response.put("status", "started");
        response.put("format", formatName);
        response.put("file", fileName);
        response.put("model", "doubao-seed-2-0-mini");
        response.put("outputDir", "generated_data/");
        response.put("message", formatName + " 生成任务已启动，请查看日志获取进度。");

        new Thread(() -> {
            try {
                task.run();
            } catch (Exception e) {
                log.error(formatName + " 生成失败", e);
            }
        }, "data-generator-" + formatName).start();

        return ResponseEntity.ok(response);
    }
}