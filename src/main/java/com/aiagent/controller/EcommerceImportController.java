package com.aiagent.controller;

import com.aiagent.ecommerce.EcommerceKnowledgeImportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 电商客服知识库导入接口
 *
 * 提供 REST API 触发 JSONL 训练数据的导入流程：
 * 1. 解析 JSONL 文件
 * 2. 数据预处理（清洗、拼接 QA 文本）
 * 3. 调用 Ollama bge-m3 生成向量
 * 4. 存入云服务器 Milvus (ecommerce_qa 集合)
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/ecommerce")
@RequiredArgsConstructor
public class EcommerceImportController {

    private final EcommerceKnowledgeImportService importService;

    /**
     * 触发完整导入
     *
     * POST /api/v1/ecommerce/import
     * 请求体: {"filePath": "E:\\AI新质力\\电商客服agent\\训练数据\\train_clean_v2_small.jsonl"}
     *
     * 注意：该操作耗时较长（约数十分钟），建议在后台异步执行
     */
    @PostMapping("/import")
    public ResponseEntity<Map<String, Object>> importKnowledge(@RequestBody Map<String, String> request) {
        String filePath = request.get("filePath");
        if (filePath == null || filePath.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "filePath 不能为空"));
        }

        log.info("收到导入请求: {}", filePath);
        Map<String, Object> response = new HashMap<>();
        response.put("status", "started");
        response.put("filePath", filePath);
        response.put("message", "导入任务已启动，请查看日志获取进度");

        new Thread(() -> {
            try {
                importService.importFromFile(filePath);
            } catch (Exception e) {
                log.error("导入失败: {}", filePath, e);
            }
        }, "ecommerce-import").start();

        return ResponseEntity.ok(response);
    }

    /**
     * 测试导入（仅处理前 N 条，验证流程）
     *
     * POST /api/v1/ecommerce/import/test
     * 请求体: {"filePath": "E:\\AI新质力\\电商客服agent\\训练数据\\test_clean_v2.jsonl", "limit": 10}
     *
     * 返回前 limit 条数据的预览，不写入 Milvus
     */
    @PostMapping("/import/test")
    public ResponseEntity<Map<String, Object>> testImport(@RequestBody Map<String, String> request) {
        String filePath = request.get("filePath");
        int limit = Integer.parseInt(request.getOrDefault("limit", "5"));

        if (filePath == null || filePath.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "filePath 不能为空"));
        }

        try {
            var allRecords = importService.parseJsonl(filePath);
            var preview = allRecords.size() > limit
                    ? allRecords.subList(0, limit)
                    : allRecords;

            log.info("测试读取: {} 条记录 (总 {} 条)", preview.size(), allRecords.size());

            Map<String, Object> response = new HashMap<>();
            response.put("totalRecords", allRecords.size());
            response.put("previewCount", preview.size());
            response.put("preview", preview.stream().map(r -> Map.of(
                    "question", r.getQuestion(),
                    "answer", r.getAnswer(),
                    "qaTextLength", r.getQaText().length()
            )).toList());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("测试读取失败", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }
}