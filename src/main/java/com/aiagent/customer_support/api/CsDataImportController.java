package com.aiagent.customer_support.api;

import com.aiagent.customer_support.application.CsDataImportService;
import com.aiagent.customer_support.config.CsProperties;
import com.aiagent.infrastructure.idempotency.IdempotencyInProgressException;
import com.aiagent.infrastructure.idempotency.IdempotencyService;
import com.aiagent.infrastructure.idempotency.IdempotencyService.ClaimStatus;
import com.aiagent.infrastructure.security.KnowledgeFileAccessPolicy;
import com.aiagent.knowledge.infrastructure.vectorstore.MilvusAdminService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * 客服知识数据导入接口。
 *
 * <p>导入任务在提交前抢占单机维护槽位，避免检查状态与异步提交之间的竞态。
 * 文件只能从配置的数据目录中选择，批次失败时保留 checkpoint 供后续续跑。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/cs/data")
public class CsDataImportController {

    private static final Set<String> ALLOWED_IMPORT_EXTENSIONS = Set.of(".jsonl");
    private static final List<String> ALL_FILES = List.of(
            "train_clean_v2_small.jsonl",
            "dev_clean_v2.jsonl",
            "test_clean_v2.jsonl");

    private final CsDataImportService csDataImportService;
    private final MilvusAdminService milvusAdminService;
    private final CsProperties csProperties;
    private final IdempotencyService idempotencyService;
    private final Executor maintenanceExecutor;
    private final KnowledgeFileAccessPolicy fileAccessPolicy;
    private final AtomicBoolean maintenanceSlot = new AtomicBoolean(false);

    @Autowired
    public CsDataImportController(
            CsDataImportService csDataImportService,
            MilvusAdminService milvusAdminService,
            CsProperties csProperties,
            IdempotencyService idempotencyService,
            @Qualifier("knowledgeMaintenanceExecutor") Executor maintenanceExecutor,
            KnowledgeFileAccessPolicy fileAccessPolicy) {
        this.csDataImportService = csDataImportService;
        this.milvusAdminService = milvusAdminService;
        this.csProperties = csProperties;
        this.idempotencyService = idempotencyService;
        this.maintenanceExecutor = maintenanceExecutor;
        this.fileAccessPolicy = fileAccessPolicy;
    }

    public CsDataImportController(CsDataImportService csDataImportService,
                                  MilvusAdminService milvusAdminService,
                                  CsProperties csProperties) {
        this(csDataImportService, milvusAdminService, csProperties, null,
                Runnable::run, new KnowledgeFileAccessPolicy());
    }

    @PostMapping("/import")
    public Map<String, Object> importData(
            @RequestParam(defaultValue = "train_clean_v2_small.jsonl") String file,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        Path filePath = resolveFilePath(file);
        String requestHash = requestHash("cs-import", file, filePath.toString(), fileSignature(filePath));
        Map<String, Object> initialResponse = Map.of(
                "success", true,
                "message", "导入任务已提交，请通过 /api/cs/data/progress 查询进度",
                "file", file);

        return launchImport("cs-import", idempotencyKey, requestHash, initialResponse, () -> {
            CsDataImportService.ImportResult result = csDataImportService.importFromJsonl(filePath.toString());
            log.info("客服数据导入完成: {}", result);
            return Map.of(
                    "success", true,
                    "message", "导入完成",
                    "file", file,
                    "result", result);
        });
    }

    public Map<String, Object> importData(String file) {
        return importData(file, null);
    }

    @PostMapping("/import-all")
    public Map<String, Object> importAll(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        Map<String, Path> resolvedFiles = new LinkedHashMap<>();
        for (String file : ALL_FILES) {
            resolvedFiles.put(file, resolveFilePath(file));
        }

        String requestHash = requestHash("cs-import-all", resolvedFiles.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + fileSignature(entry.getValue()))
                .toArray(String[]::new));
        Map<String, Object> initialResponse = Map.of(
                "success", true,
                "message", "全部客服数据导入任务已提交",
                "files", ALL_FILES);

        return launchImport("cs-import-all", idempotencyKey, requestHash, initialResponse, () -> {
            List<CsDataImportService.ImportResult> results = new ArrayList<>();
            for (Map.Entry<String, Path> entry : resolvedFiles.entrySet()) {
                log.info("开始导入客服数据文件: {}", entry.getKey());
                CsDataImportService.ImportResult result =
                        csDataImportService.importFromJsonl(entry.getValue().toString());
                results.add(result);
                log.info("客服数据文件导入完成: {} - {}", entry.getKey(), result);
            }
            return Map.of(
                    "success", true,
                    "message", "全部客服数据导入完成",
                    "files", ALL_FILES,
                    "result", results);
        });
    }

    public Map<String, Object> importAll() {
        return importAll(null);
    }

    @GetMapping("/progress")
    public Map<String, Object> getProgress() {
        long progress = csDataImportService.getProgress();
        long total = csDataImportService.getTotal();
        boolean running = csDataImportService.isRunning() || maintenanceSlot.get();

        String progressText = total <= 0
                ? "正在准备导入任务..."
                : String.format("%d / %d (%.1f%%)", progress, total, (double) progress / total * 100);

        Map<String, Object> result = new HashMap<>();
        result.put("running", running);
        result.put("progress", progress);
        result.put("total", total);
        result.put("progressStr", progressText);
        result.put("successCount", csDataImportService.getSuccessCount());
        result.put("failCount", csDataImportService.getFailCount());
        if (!running && progress > 0) {
            result.put("checkpointHint", "如上次任务失败，可重新调用导入接口从 checkpoint 继续");
        }
        return result;
    }

    @PostMapping("/finalize")
    public Map<String, Object> finalizeImport() {
        if (csDataImportService.isRunning() || !maintenanceSlot.compareAndSet(false, true)) {
            return busyResponse();
        }

        try {
            maintenanceExecutor.execute(() -> {
                try {
                    milvusAdminService.flushAll();
                    CompletableFuture<Void> indexFuture = milvusAdminService.buildAllIndexesAsync();
                    if (indexFuture != null) {
                        indexFuture.join();
                    }
                    milvusAdminService.loadAllCollections();
                    log.info("Milvus finalize 任务完成");
                } catch (RuntimeException | Error exception) {
                    log.error("Milvus finalize 任务失败", exception);
                } finally {
                    maintenanceSlot.set(false);
                }
            });
        } catch (RejectedExecutionException exception) {
            maintenanceSlot.set(false);
            return busyResponse();
        }

        return Map.of(
                "success", true,
                "message", "Milvus flush、索引构建和加载任务已提交");
    }

    @GetMapping("/files")
    public Map<String, Object> listFiles() {
        Path directory = fileAccessPolicy.requireAllowedDirectory(csProperties.getDataDir());
        try (var paths = Files.list(directory)) {
            List<String> files = paths
                    .filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.toLowerCase().endsWith(".jsonl"))
                    .sorted()
                    .toList();
            return Map.of("dataDir", directory.toString(), "files", files);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to list customer-service import files", exception);
        }
    }

    private Map<String, Object> launchImport(String operation,
                                              String requestedKey,
                                              String requestHash,
                                              Map<String, Object> initialResponse,
                                              Supplier<Map<String, Object>> task) {
        String claimedKey = null;
        String ownerToken = null;
        if (idempotencyService != null) {
            claimedKey = StringUtils.hasText(requestedKey) ? requestedKey : "auto-" + requestHash;
            var claim = idempotencyService.claim(operation, claimedKey, requestHash);
            if (claim.status() == ClaimStatus.COMPLETED) {
                return idempotencyService.readCompletedMap(claim.payload());
            }
            if (claim.status() == ClaimStatus.IN_PROGRESS) {
                throw new IdempotencyInProgressException("The idempotent import request is still processing");
            }
            ownerToken = claim.ownerToken();
        }

        if (csDataImportService.isRunning() || !maintenanceSlot.compareAndSet(false, true)) {
            releaseClaim(operation, claimedKey, requestHash, ownerToken);
            return busyResponse();
        }

        String effectiveKey = claimedKey;
        String effectiveOwnerToken = ownerToken;
        try {
            maintenanceExecutor.execute(() -> runImportTask(
                    operation, effectiveKey, requestHash, effectiveOwnerToken, task));
        } catch (RejectedExecutionException exception) {
            maintenanceSlot.set(false);
            releaseClaim(operation, effectiveKey, requestHash, effectiveOwnerToken);
            return busyResponse();
        }
        return initialResponse;
    }

    private void runImportTask(String operation,
                               String idempotencyKey,
                               String requestHash,
                               String ownerToken,
                               Supplier<Map<String, Object>> task) {
        try {
            Map<String, Object> result = task.get();
            if (idempotencyService != null && idempotencyKey != null) {
                idempotencyService.complete(operation, idempotencyKey, requestHash, ownerToken, result);
            }
        } catch (RuntimeException | Error exception) {
            releaseClaim(operation, idempotencyKey, requestHash, ownerToken);
            log.error("客服知识维护任务失败", exception);
        } finally {
            maintenanceSlot.set(false);
        }
    }

    private void releaseClaim(String operation, String idempotencyKey, String requestHash, String ownerToken) {
        if (idempotencyService != null && idempotencyKey != null) {
            idempotencyService.release(operation, idempotencyKey, requestHash, ownerToken);
        }
    }

    private Map<String, Object> busyResponse() {
        return Map.of(
                "success", false,
                "message", "已有客服知识维护任务正在运行，请稍后重试");
    }

    private Path resolveFilePath(String file) {
        return fileAccessPolicy.requireAllowedRegularFile(
                csProperties.getDataDir(), file, ALLOWED_IMPORT_EXTENSIONS);
    }

    private String requestHash(String operation, String... values) {
        if (idempotencyService == null) {
            return operation + ":" + String.join("|", values);
        }
        String[] material = new String[values.length + 1];
        material[0] = operation;
        System.arraycopy(values, 0, material, 1, values.length);
        return idempotencyService.fingerprint(material);
    }

    private String fileSignature(Path path) {
        try {
            return path + ":" + Files.size(path) + ":" + Files.getLastModifiedTime(path).toMillis();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read import file metadata", exception);
        }
    }
}
