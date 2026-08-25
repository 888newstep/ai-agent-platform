package com.aiagent.customer_support.application;

import com.aiagent.infrastructure.config.AiProperties;
import com.aiagent.ecommerce.domain.EcommerceQaPair;
import com.aiagent.ecommerce.infrastructure.repository.EcommerceQaPairRepository;
import com.aiagent.customer_support.config.CsProperties;
import com.aiagent.customer_support.application.CsImportBatchPersistenceService.PendingPair;
import com.aiagent.customer_support.application.CsImportBatchPersistenceService.VectorAssignment;
import com.aiagent.shared.data.TrainingQaParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.response.InsertResp;
import io.milvus.v2.service.utility.request.FlushReq;
import lombok.Builder;
import lombok.ToString;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 客服训练数据导入服务 — 断点续跑
 *
 * 功能：
 * 1. 流式读取 JSONL（OpenAI chat 格式），逐行解析避免 OOM
 * 2. 批量向量化（SiliconFlow BAAI/bge-m3）+ 批量入库 Milvus
 * 3. 断点续跑：每批写入后记录偏移量，崩溃后可恢复
 * 4. 同时写入 MySQL ecommerce_qa_pairs 表（MySQL 为"源"，Milvus 为"索引"）
 *
 * 数据格式：OpenAI chat 格式 JSONL
 * {"messages": [{"role": "system", "content": "..."}, {"role": "user", "content": "..."}, {"role": "assistant", "content": "..."}]}
 */
@Slf4j
@Service
public class CsDataImportService {

    private final MilvusClientV2 milvusClient;
    private final EmbeddingModel embeddingModel;
    private final ObjectMapper objectMapper;
    private final CsImportBatchPersistenceService batchPersistenceService;
    private final CsProperties csProperties;
    private final AiProperties aiProperties;

    private static final String COLLECTION_NAME = "ecommerce_qa";
    private static final String CHECKPOINT_SUFFIX = ".checkpoint";


    /** 当前进度（行号） */
    private final AtomicLong currentProgress = new AtomicLong(0);
    /** 总行数 */
    private final AtomicLong totalCount = new AtomicLong(0);
    /** 成功条数 */
    private final AtomicLong successCount = new AtomicLong(0);
    /** 失败条数 */
    private final AtomicLong failCount = new AtomicLong(0);
    /** 是否正在运行 */
    private volatile boolean running = false;

    public CsDataImportService(@Autowired(required = false) MilvusClientV2 milvusClient,
                               EmbeddingModel embeddingModel,
                               ObjectMapper objectMapper,
                               EcommerceQaPairRepository qaPairRepository,
                               CsProperties csProperties,
                               AiProperties aiProperties) {
        this(milvusClient, embeddingModel, objectMapper,
                new CsImportBatchPersistenceService(qaPairRepository), csProperties, aiProperties);
    }

    @Autowired
    public CsDataImportService(@Autowired(required = false) MilvusClientV2 milvusClient,
                               EmbeddingModel embeddingModel,
                               ObjectMapper objectMapper,
                               CsImportBatchPersistenceService batchPersistenceService,
                               CsProperties csProperties,
                               AiProperties aiProperties) {
        this.milvusClient = milvusClient;
        this.embeddingModel = embeddingModel;
        this.objectMapper = objectMapper;
        this.batchPersistenceService = batchPersistenceService;
        this.csProperties = csProperties;
        this.aiProperties = aiProperties;
    }

    private void ensureMilvusWritable() {
        if (aiProperties.getVectorStore().getMilvus().isReadOnly()) {
            throw new IllegalStateException("Milvus is read-only; data import is disabled");
        }
    }

    /**
     * 从 JSONL 文件导入数据（断点续跑）
     *
     * @param filePath JSONL 文件绝对路径
     * @return 导入结果
     */
    public ImportResult importFromJsonl(String filePath) {
        ensureMilvusWritable();
        Path jsonPath = Paths.get(filePath);
        if (!Files.exists(jsonPath)) {
            throw new IllegalArgumentException("文件不存在: " + filePath);
        }

        if (milvusClient == null) {
            throw new IllegalStateException("Milvus 客户端不可用，请检查连接配置");
        }

        running = true;
        currentProgress.set(0);
        totalCount.set(0);
        successCount.set(0);
        failCount.set(0);

        String fileName = jsonPath.getFileName().toString();

        try {
            Path checkpointPath = getCheckpointPath(jsonPath);
            long skipLines = readCheckpoint(checkpointPath);
            log.info("断点文件: {}，已处理 {} 行", checkpointPath, skipLines);

            // 估算总行数
            long estimated = countLines(jsonPath);
            totalCount.set(estimated);
            log.info("预估总行数: {}", estimated);

            // 执行导入
            ImportResult result = doImport(jsonPath, checkpointPath, skipLines, fileName);

            // 删除断点文件
            Files.deleteIfExists(checkpointPath);
            log.info("导入完成: {} (成功: {}, 失败: {})", result.fileName, result.successCount, result.failCount);

            return result;
        } catch (Exception e) {
            log.error("导入过程异常", e);
            throw new RuntimeException("导入失败: " + e.getMessage(), e);
        } finally {
            running = false;
        }
    }

    /**
     * 逐行解析 JSONL，批量处理
     */
    private ImportResult doImport(Path jsonPath, Path checkpointPath,
                                  long skipLines, String fileName) throws IOException {
        ImportResult result = new ImportResult();
        result.fileName = fileName;
        result.filePath = jsonPath.toString();
        long processed = skipLines;

        List<QaRecord> batchRecords = new ArrayList<>();
        int flushCounter = 0;

        try (BufferedReader reader = Files.newBufferedReader(jsonPath, StandardCharsets.UTF_8)) {
            String line;
            long lineNum = 0;

            // 跳过已处理的行
            while (lineNum < skipLines && (line = reader.readLine()) != null) {
                lineNum++;
            }

            // 继续读取剩余行
            while ((line = reader.readLine()) != null) {
                lineNum++;
                line = line.trim();
                if (line.isEmpty()) continue;

                try {
                    QaRecord record = parseLine(line, lineNum, fileName);
                    if (record != null) {
                        batchRecords.add(record);
                    } else {
                        failCount.incrementAndGet();
                        result.failCount++;
                    }
                } catch (Exception e) {
                    log.warn("第 {} 行解析失败，跳过: {}", lineNum, e.getMessage());
                    failCount.incrementAndGet();
                    result.failCount++;
                }

                // 达到 batch 大小，批量处理
                if (batchRecords.size() >= csProperties.getBatchSize()) {
                    processBatch(batchRecords, result);
                    processed = lineNum;
                    flushCounter++;

                    // 每 28 批 flush 一次
                    if (flushCounter % 28 == 0) {
                        flushMilvus();
                        log.info("自动 flush [{}]（第 {} 次，已处理 {} 行）", COLLECTION_NAME, flushCounter / 28, processed);
                    }

                    // 写断点
                    writeCheckpoint(checkpointPath, processed);
                    updateProgress(processed, result);

                    batchRecords.clear();
                }
            }

            // 处理剩余不足一批的数据
            if (!batchRecords.isEmpty()) {
                processBatch(batchRecords, result);
                processed = lineNum;
                writeCheckpoint(checkpointPath, processed);
                updateProgress(processed, result);
            }

            result.totalProcessed = result.successCount + result.failCount;
            flushMilvus();
            log.info("最终 flush [{}] 完成，共处理 {} 条", COLLECTION_NAME, result.totalProcessed);
        }

        return result;
    }

    private void updateProgress(long processed, ImportResult result) {
        currentProgress.set(processed);
        successCount.set(result.successCount);
        failCount.set(result.failCount);
    }

    /**
     * 解析单行 JSON，提取 QA 对
     */
    private QaRecord parseLine(String jsonLine, long lineNum, String fileName) {
        try {
            TrainingQaParser.TrainingQa parsed = TrainingQaParser.parse(objectMapper, jsonLine)
                    .orElse(null);
            if (parsed == null) {
                log.warn("第 {} 行: messages 字段不足 3 条", lineNum);
                return null;
            }
            if (!parsed.hasQuestion()) {
                log.warn("第 {} 行: user 内容为空，跳过", lineNum);
                return null;
            }

            String cleanQuestion = parsed.question();
            String cleanAnswer = parsed.answer();
            String qaText = parsed.embeddingText();

            // 截断到 3072 字符（匹配 schema 的 maxLength）
            if (qaText.length() > 3072) {
                qaText = qaText.substring(0, 3072);
            }
            if (cleanQuestion.length() > 1024) {
                cleanQuestion = cleanQuestion.substring(0, 1024);
            }

            String category = fileNameToCategory(fileName);

            return QaRecord.builder()
                    .question(cleanQuestion)
                    .answer(cleanAnswer)
                    .qaText(qaText)
                    .category(category)
                    .sourceFile(fileName)
                    .recordHash(recordHash(fileName, cleanQuestion, cleanAnswer, category))
                    .build();
        } catch (Exception e) {
            log.warn("第 {} 行 JSON 解析失败: {}", lineNum, e.getMessage());
            return null;
        }
    }

    /**
     * 从文件名推断分类
     */
    private String fileNameToCategory(String fileName) {
        if (fileName.contains("train")) return "train";
        if (fileName.contains("dev")) return "dev";
        if (fileName.contains("test")) return "test";
        return "general";
    }

    private String recordHash(String sourceFile, String question, String answer, String category) {
        String material = String.join("\u001f",
                sourceFile == null ? "" : sourceFile,
                question == null ? "" : question,
                answer == null ? "" : answer,
                category == null ? "" : category);
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    /**
     * 处理一批数据：向量化 → 存 MySQL → 存 Milvus
     */
    private void processBatch(List<QaRecord> records, ImportResult result) {
        long startTime = System.currentTimeMillis();
        try {
            // 1. 批量向量化（使用 LangChain4j EmbeddingModel）
            List<TextSegment> segments = records.stream()
                    .map(QaRecord::getQaText)
                    .map(TextSegment::from)
                    .toList();
            List<Embedding> embeddings = embeddingModel.embedAll(segments).content();

            if (embeddings == null || embeddings.size() != records.size()) {
                throw new IllegalStateException("Embedding result count does not match the import batch");
            }

            for (Embedding embedding : embeddings) {
                if (embedding == null) {
                    throw new IllegalStateException("Embedding service returned an empty vector");
                }
            }

            // 2. 先保存到 MySQL（获取自增 ID）
            long vectorTimestamp = System.currentTimeMillis() / 1000;
            List<PendingPair> pendingPairs = batchPersistenceService.prepareBatch(records).pendingPairs();
            List<QaRecord> recordsToStore = new ArrayList<>();
            List<Embedding> embeddingsToStore = new ArrayList<>();
            List<EcommerceQaPair> pairsToStore = new ArrayList<>();

            for (PendingPair pendingPair : pendingPairs) {
                recordsToStore.add(records.get(pendingPair.recordIndex()));
                embeddingsToStore.add(embeddings.get(pendingPair.recordIndex()));
                pairsToStore.add(pendingPair.pair());
            }

            if (pairsToStore.isEmpty()) {
                result.successCount += records.size();
                result.totalProcessed += records.size();
                return;
            }

            // 3. 构建 Milvus 行数据
            List<JsonObject> rows = new ArrayList<>();
            for (int i = 0; i < recordsToStore.size(); i++) {
                QaRecord record = recordsToStore.get(i);
                float[] vector = embeddingsToStore.get(i).vector();
                Long qaPairId = pairsToStore.get(i).getId();

                JsonObject row = new JsonObject();
                row.addProperty("question", record.getQuestion());
                row.addProperty("answer", record.getAnswer());
                row.addProperty("qa_text", record.getQaText());
                row.addProperty("qa_pair_id", qaPairId);
                row.addProperty("category", record.getCategory());
                row.add("embedding", toJsonArray(vector));
                row.addProperty("ts", vectorTimestamp);
                rows.add(row);
            }

            // 4. 批量插入 Milvus
            deleteVectorsByQaPairIds(pairsToStore);
            List<Object> primaryKeys = List.of();
            boolean vectorIdsPersisted = false;
            try {
                InsertResp insertResponse = milvusClient.insert(InsertReq.builder()
                        .collectionName(COLLECTION_NAME)
                        .data(rows)
                        .build());

                primaryKeys = insertResponse == null ? null : insertResponse.getPrimaryKeys();
                if (primaryKeys == null || primaryKeys.size() != pairsToStore.size()) {
                    throw new IllegalStateException("Milvus did not return all inserted vector identifiers");
                }
                List<VectorAssignment> assignments = new ArrayList<>();
                for (int i = 0; i < pairsToStore.size(); i++) {
                    assignments.add(new VectorAssignment(
                            pairsToStore.get(i).getId(), String.valueOf(primaryKeys.get(i))));
                }
                batchPersistenceService.updateVectorIds(assignments);
                vectorIdsPersisted = true;
            } finally {
                if (!vectorIdsPersisted) {
                    deleteInsertedVectorsQuietly(primaryKeys);
                }
            }

            // 5. 更新统计
            int successThisBatch = records.size();
            result.successCount += successThisBatch;
            result.totalProcessed += records.size();

            long elapsed = System.currentTimeMillis() - startTime;
            log.debug("批次完成: {} 条 (成功: {}, 耗时: {}ms, 累计: {})",
                    records.size(), successThisBatch, elapsed, result.successCount);

        } catch (Exception e) {
            failCount.addAndGet(records.size());
            log.error("批次处理失败（{} 条），停止导入并保留 checkpoint", records.size(), e);
            throw new IllegalStateException("Import batch processing failed", e);
        }
    }

    private void deleteVectorsByQaPairIds(List<EcommerceQaPair> pairs) {
        String ids = pairs.stream()
                .map(EcommerceQaPair::getId)
                .filter(Objects::nonNull)
                .map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(","));
        if (ids.isEmpty()) {
            throw new IllegalStateException("Imported QA records do not have MySQL identifiers");
        }
        milvusClient.delete(DeleteReq.builder()
                .collectionName(COLLECTION_NAME)
                .filter("qa_pair_id in [" + ids + "]")
                .build());
    }

    private void deleteInsertedVectorsQuietly(List<Object> primaryKeys) {
        if (primaryKeys == null || primaryKeys.isEmpty()) {
            return;
        }
        try {
            String ids = primaryKeys.stream()
                    .map(String::valueOf)
                    .map(Long::parseLong)
                    .map(String::valueOf)
                    .collect(java.util.stream.Collectors.joining(","));
            milvusClient.delete(DeleteReq.builder()
                    .collectionName(COLLECTION_NAME)
                    .filter("id in [" + ids + "]")
                    .build());
        } catch (Exception cleanupException) {
            log.warn("清理未提交的 Milvus 向量失败，将由下次 qa_pair_id 重放覆盖: {}",
                    cleanupException.getMessage());
        }
    }

    // =============================================
    // Flush
    // =============================================

    private void flushMilvus() {
        if (milvusClient == null) {
            throw new IllegalStateException("Milvus client is unavailable");
        }
        milvusClient.flush(FlushReq.builder()
                .collectionNames(List.of(COLLECTION_NAME))
                .build());
    }

    // =============================================
    // 断点管理
    // =============================================

    private Path getCheckpointPath(Path jsonPath) {
        String fileName = jsonPath.getFileName().toString() + CHECKPOINT_SUFFIX;
        return jsonPath.resolveSibling(fileName);
    }

    private long readCheckpoint(Path checkpointPath) {
        if (!Files.exists(checkpointPath)) {
            return 0;
        }
        try {
            String content = Files.readString(checkpointPath, StandardCharsets.UTF_8).trim();
            return Long.parseLong(content);
        } catch (Exception e) {
            throw new IllegalStateException("Checkpoint file is invalid: " + checkpointPath, e);
        }
    }

    private void writeCheckpoint(Path checkpointPath, long processed) {
        Path temporaryPath = checkpointPath.resolveSibling(checkpointPath.getFileName() + ".tmp");
        try {
            Files.writeString(temporaryPath, String.valueOf(processed), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                Files.move(temporaryPath, checkpointPath,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporaryPath, checkpointPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to persist import checkpoint", e);
        }
    }

    // =============================================
    // 工具方法
    // =============================================

    /**
     * 统计文件行数（快速遍历，不加载到内存）
     */
    private long countLines(Path path) throws IOException {
        long count = 0;
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            while (reader.readLine() != null) {
                count++;
            }
        }
        return count;
    }

    /**
     * 文本清洗：去除多余空白、换行，减少 token 消耗
     */
    /**
     * 将 float[] 转换为 Gson JsonArray，供 Milvus SDK 使用
     */
    private JsonArray toJsonArray(float[] vector) {
        JsonArray arr = new JsonArray();
        for (float v : vector) {
            arr.add(v);
        }
        return arr;
    }

    // =============================================
    // 状态查询
    // =============================================

    public long getProgress() { return currentProgress.get(); }
    public long getTotal() { return totalCount.get(); }
    public long getSuccessCount() { return successCount.get(); }
    public long getFailCount() { return failCount.get(); }
    public boolean isRunning() { return running; }

    // =============================================
    // 内部类
    // =============================================

    @Value
    @Builder
    public static class QaRecord {
        String question;
        String answer;
        String qaText;
        String category;
        String sourceFile;
        String recordHash;
    }

    @ToString
    public static class ImportResult {
        public String fileName;
        public String filePath;
        public long totalProcessed;
        public long successCount;
        public long failCount;
    }
}
