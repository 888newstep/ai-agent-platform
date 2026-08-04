package com.aiagent.cs;

import com.aiagent.ecommerce.EcommerceKnowledgeImportService;
import com.aiagent.service.MilvusAdminService;
import com.aiagent.config.CsProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 客服训练数据导入控制器
 *
 * 用于从 JSONL 文件导入训练数据到 MySQL + Milvus（cs_agent 数据库）
 * 支持断点续跑、进度查询、导入完成后建索引+加载
 *
 * 数据格式：OpenAI chat 格式 JSONL
 * 来源：E:\AI新质力\电商客服agent\训练数据
 */
@Slf4j
@RestController
@RequestMapping("/api/cs/data")
public class CsDataImportController {

    private final CsDataImportService csDataImportService;
    private final MilvusAdminService milvusAdminService;
    private final CsProperties csProperties;


    public CsDataImportController(CsDataImportService csDataImportService,
                                  MilvusAdminService milvusAdminService,
                                  CsProperties csProperties) {
        this.csDataImportService = csDataImportService;
        this.milvusAdminService = milvusAdminService;
        this.csProperties = csProperties;
    }

    /**
     * 启动训练数据导入（异步）
     * 默认导入 train_clean_v2_small.jsonl
     * 支持断点续跑：崩溃后重新调用即可从断点恢复
     *
     * @param file 要导入的文件名（如 train_clean_v2_small.jsonl）
     * @return 导入任务状态
     */
    @PostMapping("/import")
    public Map<String, Object> importData(
            @RequestParam(defaultValue = "train_clean_v2_small.jsonl") String file) {

        if (csDataImportService.isRunning()) {
            return Map.of("success", false, "message", "导入任务正在运行中，请先查询进度");
        }

        String filePath = csProperties.getDataDir().endsWith("/") ? csProperties.getDataDir() + file : csProperties.getDataDir() + "/" + file;

        CompletableFuture.runAsync(() -> {
            try {
                CsDataImportService.ImportResult result = csDataImportService.importFromJsonl(filePath);
                log.info("===== 数据导入完成: {} =====", result);
            } catch (Exception e) {
                log.error("导入失败: {}", e.getMessage(), e);
            }
        });

        return Map.of(
                "success", true,
                "message", "导入任务已异步启动，请调用 /api/cs/data/progress 查看进度",
                "file", file
        );
    }

    /**
     * 导入所有训练文件（按顺序）
     * 依次导入：train_clean_v2_small.jsonl → dev_clean_v2.jsonl → test_clean_v2.jsonl
     */
    @PostMapping("/import-all")
    public Map<String, Object> importAll() {
        if (csDataImportService.isRunning()) {
            return Map.of("success", false, "message", "导入任务正在运行中，请先查询进度");
        }

        List<String> files = List.of(
                "train_clean_v2_small.jsonl",
                "dev_clean_v2.jsonl",
                "test_clean_v2.jsonl"
        );

        CompletableFuture.runAsync(() -> {
            for (String file : files) {
                String filePath = csProperties.getDataDir().endsWith("/") ? csProperties.getDataDir() + file : csProperties.getDataDir() + "/" + file;
                try {
                    log.info("===== 开始导入: {} =====", file);
                    CsDataImportService.ImportResult result = csDataImportService.importFromJsonl(filePath);
                    log.info("===== 导入完成: {} - {} =====", file, result);
                } catch (Exception e) {
                    log.error("导入 [{}] 失败: {}", file, e.getMessage(), e);
                }
            }
            log.info("===== 全部文件导入完成 =====");
        });

        return Map.of(
                "success", true,
                "message", "分批导入任务已异步启动，将依次导入 3 个文件",
                "files", files
        );
    }

    /**
     * 查询导入进度
     */
    @GetMapping("/progress")
    public Map<String, Object> getProgress() {
        long progress = csDataImportService.getProgress();
        long total = csDataImportService.getTotal();
        boolean running = csDataImportService.isRunning();

        String progressStr;
        if (total <= 0) {
            progressStr = "正在统计总行数...";
        } else {
            double pct = total > 0 ? (double) progress / total * 100 : 0;
            progressStr = String.format("%d / %d (%.1f%%)", progress, total, pct);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("running", running);
        result.put("progress", progress);
        result.put("total", total);
        result.put("progressStr", progressStr);
        result.put("successCount", csDataImportService.getSuccessCount());
        result.put("failCount", csDataImportService.getFailCount());

        if (!running && progress > 0) {
            result.put("checkpointHint", "上次导入中断，已记录断点。重新调用 POST /api/cs/data/import 即可续跑");
        }

        return result;
    }

    /**
     * 导入完成后，建索引 + 加载（一步到位）
     */
    @PostMapping("/finalize")
    public Map<String, Object> finalizeImport() {
        if (csDataImportService.isRunning()) {
            return Map.of("success", false, "message", "导入任务仍在运行，请先等待完成");
        }

        CompletableFuture.runAsync(() -> {
            // 1. flush 所有数据落盘
            milvusAdminService.flushAll();

            // 2. 为所有集合建索引
            milvusAdminService.buildAllIndexesAsync();

            // 3. 加载所有集合
            milvusAdminService.loadAllCollections();
        });

        return Map.of(
                "success", true,
                "message", "全量建索引+加载已异步启动，请通过日志或 Attu 监控进度"
        );
    }

    /**
     * 列出可用的训练数据文件
     */
    @GetMapping("/files")
    public Map<String, Object> listFiles() {
        List<String> files = new ArrayList<>();
        java.io.File dir = new java.io.File(csProperties.getDataDir());
        if (dir.exists() && dir.isDirectory()) {
            java.io.File[] jsonlFiles = dir.listFiles((d, name) -> name.endsWith(".jsonl"));
            if (jsonlFiles != null) {
                for (java.io.File f : jsonlFiles) {
                    files.add(f.getName());
                }
            }
        }
        return Map.of("dataDir", csProperties.getDataDir(), "files", files);
    }
}