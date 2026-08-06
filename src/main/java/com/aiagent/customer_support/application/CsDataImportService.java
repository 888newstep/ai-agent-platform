package com.aiagent.customer_support.application;

import com.aiagent.ecommerce.domain.EcommerceQaPair;
import com.aiagent.ecommerce.infrastructure.repository.EcommerceQaPairRepository;
import com.aiagent.customer_support.config.CsProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.utility.request.FlushReq;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
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
    private final EcommerceQaPairRepository qaPairRepository;
    private final CsProperties csProperties;

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
                               CsProperties csProperties) {
        this.milvusClient = milvusClient;
        this.embeddingModel = embeddingModel;
        this.objectMapper = objectMapper;
        this.qaPairRepository = qaPairRepository;
        this.csProperties = csProperties;
    }

    /**
     * 从 JSONL 文件导入数据（断点续跑）
     *
     * @param filePath JSONL 文件绝对路径
     * @return 导入结果
     */
    public ImportResult importFromJsonl(String filePath) {
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

        // 批次缓存
        List<QaRecord> batchRecords = new ArrayList<>();
        List<String> batchTexts = new ArrayList<>();
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
                        batchTexts.add(record.getQaText());
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
                    processBatch(batchRecords, batchTexts, result);
                    processed = lineNum;
                    flushCounter++;

                    // 每 28 批 flush 一次
                    if (flushCounter % 28 == 0) {
                        flushMilvus();
                        log.info("自动 flush [{}]（第 {} 次，已处理 {} 行）", COLLECTION_NAME, flushCounter / 28, processed);
                    }

                    // 写断点
                    writeCheckpoint(checkpointPath, processed);
                    currentProgress.set(processed);
                    successCount.set(result.successCount);
                    failCount.set(result.failCount);

                    batchRecords.clear();
                    batchTexts.clear();
                }
            }

            // 处理剩余不足一批的数据
            if (!batchRecords.isEmpty()) {
                processBatch(batchRecords, batchTexts, result);
                processed = lineNum;
                writeCheckpoint(checkpointPath, processed);
                currentProgress.set(processed);
            }

            // 最终 flush
            flushMilvus();
            log.info("最终 flush [{}] 完成，共处理 {} 条", COLLECTION_NAME, result.totalProcessed);
        }

        result.totalProcessed = result.successCount + result.failCount;
        return result;
    }

    /**
     * 解析单行 JSON，提取 QA 对
     */
    @SuppressWarnings("unchecked")
    private QaRecord parseLine(String jsonLine, long lineNum, String fileName) {
        try {
            Map<String, Object> root = objectMapper.readValue(jsonLine, Map.class);
            List<Map<String, Object>> messages = (List<Map<String, Object>>) root.get("messages");

            if (messages == null || messages.size() < 3) {
                log.warn("第 {} 行: messages 字段不足 3 条", lineNum);
                return null;
            }

            String userContent = "";
            String assistantContent = "";

            for (Map<String, Object> msg : messages) {
                String role = (String) msg.get("role");
                String content = (String) msg.get("content");
                if (content == null) content = "";

                switch (role) {
                    case "user" -> userContent = content;
                    case "assistant" -> assistantContent = content;
                }
            }

            if (userContent.isEmpty()) {
                log.warn("第 {} 行: user 内容为空，跳过", lineNum);
                return null;
            }

            // 清洗文本
            String cleanQuestion = cleanText(userContent);
            String cleanAnswer = cleanText(assistantContent);

            // 构建 Embedding 文本：用户问题 + 客服回答 拼接
            String qaText = "用户问题：" + cleanQuestion + " 客服回答：" + cleanAnswer;

            // 截断到 3072 字符（匹配 schema 的 maxLength）
            if (qaText.length() > 3072) {
                qaText = qaText.substring(0, 3072);
            }
            if (cleanQuestion.length() > 1024) {
                cleanQuestion = cleanQuestion.substring(0, 1024);
            }

            return QaRecord.builder()
                    .question(cleanQuestion)
                    .answer(cleanAnswer)
                    .qaText(qaText)
                    .category(fileNameToCategory(fileName))
                    .sourceFile(fileName)
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

    /**
     * 处理一批数据：向量化 → 存 MySQL → 存 Milvus
     */
    @Transactional
    protected void processBatch(List<QaRecord> records, List<String> texts, ImportResult result) {
        long startTime = System.currentTimeMillis();
        try {
            // 1. 批量向量化（使用 LangChain4j EmbeddingModel）
            List<TextSegment> segments = texts.stream().map(TextSegment::from).toList();
            List<Embedding> embeddings = embeddingModel.embedAll(segments).content();

            // 2. 过滤失败记录
            List<QaRecord> validRecords = new ArrayList<>();
            List<Embedding> validEmbeddings = new ArrayList<>();
            for (int i = 0; i < embeddings.size(); i++) {
                if (embeddings.get(i) != null) {
                    validRecords.add(records.get(i));
                    validEmbeddings.add(embeddings.get(i));
                } else {
                    result.failCount++;
                    failCount.incrementAndGet();
                }
            }

            if (validRecords.isEmpty()) {
                log.warn("批次所有记录向量化失败，跳过");
                result.failCount += records.size();
                failCount.addAndGet(records.size());
                return;
            }

            // 3. 先保存到 MySQL（获取自增 ID）
            long timestamp = System.currentTimeMillis() / 1000;
            List<EcommerceQaPair> savedPairs = new ArrayList<>();
            for (QaRecord record : validRecords) {
                EcommerceQaPair pair = EcommerceQaPair.builder()
                        .question(record.getQuestion())
                        .answer(record.getAnswer())
                        .qaText(record.getQaText())
                        .category(record.getCategory())
                        .sourceFile(record.getSourceFile())
                        .status(1)
                        .build();
                savedPairs.add(qaPairRepository.save(pair));
            }

            // 4. 构建 Milvus 行数据
            List<JsonObject> rows = new ArrayList<>();
            for (int i = 0; i < validRecords.size(); i++) {
                QaRecord record = validRecords.get(i);
                float[] vector = validEmbeddings.get(i).vector();
                Long qaPairId = savedPairs.get(i).getId();

                JsonObject row = new JsonObject();
                row.addProperty("question", record.getQuestion());
                row.addProperty("answer", record.getAnswer());
                row.addProperty("qa_text", record.getQaText());
                row.addProperty("qa_pair_id", qaPairId);
                row.addProperty("category", record.getCategory());
                row.add("embedding", toJsonArray(vector));
                row.addProperty("ts", timestamp);
                rows.add(row);
            }

            // 5. 批量插入 Milvus
            milvusClient.insert(InsertReq.builder()
                    .collectionName(COLLECTION_NAME)
                    .data(rows)
                    .build());

            // 6. 更新统计
            int successThisBatch = validRecords.size();
            result.successCount += successThisBatch;
            result.totalProcessed += records.size();
            successCount.addAndGet(successThisBatch);

            long elapsed = System.currentTimeMillis() - startTime;
            log.debug("批次完成: {} 条 (成功: {}, 耗时: {}ms, 累计: {})",
                    records.size(), successThisBatch, elapsed, result.successCount);

        } catch (Exception e) {
            log.error("批次处理失败（{} 条），放弃该批次: {}", records.size(), e.getMessage());
            result.failCount += records.size();
            failCount.addAndGet(records.size());
            result.totalProcessed += records.size();
        }
    }

    // =============================================
    // Flush
    // =============================================

    private void flushMilvus() {
        if (milvusClient == null) return;
        try {
            milvusClient.flush(FlushReq.builder()
                    .collectionNames(List.of(COLLECTION_NAME))
                    .build());
        } catch (Exception e) {
            log.warn("flush [{}] 失败: {}", COLLECTION_NAME, e.getMessage());
        }
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
            log.warn("读取断点文件失败，将从 0 开始: {}", e.getMessage());
            return 0;
        }
    }

    private void writeCheckpoint(Path checkpointPath, long processed) {
        try {
            Files.writeString(checkpointPath, String.valueOf(processed), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("写入断点文件失败: {}", e.getMessage());
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
    private String cleanText(String text) {
        if (text == null) return "";
        return text.trim()
                .replaceAll("\\s+", " ")
                .replaceAll("[\\r\\n]+", " ");
    }

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

    public static class QaRecord {
        private final String question;
        private final String answer;
        private final String qaText;
        private final String category;
        private final String sourceFile;

        private QaRecord(String question, String answer, String qaText, String category, String sourceFile) {
            this.question = question;
            this.answer = answer;
            this.qaText = qaText;
            this.category = category;
            this.sourceFile = sourceFile;
        }

        public static QaRecordBuilder builder() { return new QaRecordBuilder(); }

        public String getQuestion() { return question; }
        public String getAnswer() { return answer; }
        public String getQaText() { return qaText; }
        public String getCategory() { return category; }
        public String getSourceFile() { return sourceFile; }

        public static class QaRecordBuilder {
            private String question;
            private String answer;
            private String qaText;
            private String category;
            private String sourceFile;
            public QaRecordBuilder question(String v) { this.question = v; return this; }
            public QaRecordBuilder answer(String v) { this.answer = v; return this; }
            public QaRecordBuilder qaText(String v) { this.qaText = v; return this; }
            public QaRecordBuilder category(String v) { this.category = v; return this; }
            public QaRecordBuilder sourceFile(String v) { this.sourceFile = v; return this; }
            public QaRecord build() { return new QaRecord(question, answer, qaText, category, sourceFile); }
        }
    }

    public static class ImportResult {
        public String fileName;
        public String filePath;
        public long totalProcessed;
        public long successCount;
        public long failCount;

        @Override
        public String toString() {
            return String.format("ImportResult{file=%s, total=%d, success=%d, fail=%d}",
                    fileName, totalProcessed, successCount, failCount);
        }
    }
}