package com.aiagent.ecommerce.application;

import com.aiagent.ecommerce.config.EcommerceProperties;
import com.aiagent.ecommerce.domain.EcommerceQaPair;
import com.aiagent.ecommerce.infrastructure.repository.EcommerceQaPairRepository;
import com.aiagent.infrastructure.config.AiProperties;
import com.aiagent.shared.data.DataCleaner;
import com.aiagent.shared.data.TrainingQaParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.InsertReq;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 电商客服知识库导入服务 v2
 *
 * 相对 v1 的改进：
 * 1. 数据清洗：文本归一化 + 寒暄/过短/纯符号噪音过滤（DataCleaner）
 * 2. 内容哈希去重：recordHash = SHA-256(question || answer)，配合 DB 唯一索引实现幂等/断点续传
 * 3. 意图分类落地：QaClassifier 关键词规则，分类写入 category 字段（对齐 silver-v2 六类意图）
 * 4. Embedding 统一：复用 LangChain4j EmbeddingModel（默认 siliconflow bge-m3），与查询侧一致；
 *    移除 v1 中硬编码本地 Ollama 的 RestTemplate 调用
 * 5. 工程健壮性：批次级提交（每批 saveAll+flush，失败不影响已提交批次）、Milvus 批次容错
 */
@Slf4j
@Service
public class EcommerceKnowledgeImportService {

    private final MilvusClientV2 milvusClient;
    private final ObjectMapper objectMapper;
    private final EcommerceQaPairRepository qaPairRepository;
    private final EcommerceProperties ecommerceProperties;
    private final AiProperties aiProperties;
    private final EmbeddingModel embeddingModel;
    private final QaClassifier qaClassifier;

    private static final String COLLECTION_NAME = "ecommerce_qa";
    private static final String HASH_SEPARATOR = "||";

    public EcommerceKnowledgeImportService(
            @Autowired(required = false) MilvusClientV2 milvusClient,
            ObjectMapper objectMapper,
            EcommerceQaPairRepository qaPairRepository,
            EcommerceProperties ecommerceProperties,
            AiProperties aiProperties,
            EmbeddingModel embeddingModel,
            QaClassifier qaClassifier) {
        this.milvusClient = milvusClient;
        this.objectMapper = objectMapper;
        this.qaPairRepository = qaPairRepository;
        this.ecommerceProperties = ecommerceProperties;
        this.aiProperties = aiProperties;
        this.embeddingModel = embeddingModel;
        this.qaClassifier = qaClassifier;
    }

    // =============================================
    // 1. 数据解析 + 清洗 + 去重指纹 + 分类
    // =============================================

    /**
     * 从 JSONL 文件解析原始 QA 记录（含清洗与分类）。
     */
    public List<QaRecord> parseJsonl(String filePath) throws Exception {
        List<QaRecord> records = new ArrayList<>();
        Path path = Paths.get(filePath);
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("文件不存在: " + filePath);
        }

        int lineNum = 0;
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                lineNum++;
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                try {
                    QaRecord record = parseJsonLine(line, lineNum);
                    if (record != null) {
                        records.add(record);
                    }
                } catch (Exception e) {
                    log.warn("第 {} 行解析失败，跳过: {}", lineNum, e.getMessage());
                }
            }
        }

        log.info("JSONL 解析完成: {} 条有效记录 (总行数: {})", records.size(), lineNum);
        return records;
    }

    /**
     * 解析单行 JSON → 清洗 → 去重指纹 → 分类。
     */
    private QaRecord parseJsonLine(String jsonLine, int lineNum) throws JsonProcessingException {
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

        String question = DataCleaner.normalize(parsed.question());
        String answer = DataCleaner.normalize(parsed.answer());

        // 无信息量噪音过滤
        if (DataCleaner.isNoise(question, answer)) {
            log.debug("第 {} 行: 判定为无信息量噪音，跳过: [{} | {}]", lineNum, question, answer);
            return null;
        }

        String category = qaClassifier.classify(question, answer);
        return QaRecord.builder()
                .question(question)
                .answer(answer)
                .qaText("用户问题：" + question + " 客服回答：" + answer)
                .systemPrompt(parsed.systemPrompt())
                .category(category)
                .recordHash(recordHash(question, answer))
                .build();
    }

    /** 内容哈希：SHA-256(归一化 question || answer)。 */
    public static String recordHash(String question, String answer) {
        String raw = DataCleaner.normalize(question) + HASH_SEPARATOR + DataCleaner.normalize(answer);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    // =============================================
    // 2. Embedding 生成（统一 EmbeddingModel，默认 siliconflow bge-m3）
    // =============================================

    /**
     * 批量生成向量。批量失败后逐条重试降级。
     */
    public List<List<Float>> batchGenerateEmbedding(List<String> texts) {
        List<TextSegment> segments = texts.stream().map(TextSegment::from).toList();
        try {
            Response<List<Embedding>> response = embeddingModel.embedAll(segments);
            List<List<Float>> result = new ArrayList<>(response.content().size());
            for (Embedding embedding : response.content()) {
                float[] vector = embedding.vector();
                List<Float> list = new ArrayList<>(vector.length);
                for (float v : vector) {
                    list.add(v);
                }
                result.add(list);
            }
            return result;
        } catch (Exception e) {
            log.error("批量生成向量失败 (batch={})，降级逐条重试", texts.size(), e);
            List<List<Float>> results = new ArrayList<>();
            for (String text : texts) {
                try {
                    Response<Embedding> single = embeddingModel.embed(TextSegment.from(text));
                    float[] vector = single.content().vector();
                    List<Float> list = new ArrayList<>(vector.length);
                    for (float v : vector) {
                        list.add(v);
                    }
                    results.add(list);
                } catch (Exception ex) {
                    log.error("单条生成向量失败: {}", text.substring(0, Math.min(40, text.length())), ex);
                    results.add(null);
                }
            }
            return results;
        }
    }

    // =============================================
    // 3. 批量导入（幂等 + 批次级提交）
    // =============================================

    /**
     * 执行完整的导入流程：解析 → 清洗 → 分类 → 去重 → 向量化 → 存 MySQL → 存 Milvus。
     *
     * 设计原则：
     * - MySQL 是"源"，Milvus 是"索引"，通过 qa_pair_id 关联
     * - 幂等：record_hash 唯一索引，重复导入自动跳过，支持断点续传
     * - 批次级提交：每批 saveAll+flush 独立事务，中途失败不影响已提交批次
     */
    public ImportResult importFromFile(String filePath) throws Exception {
        ensureMilvusWritable();
        if (milvusClient == null) {
            throw new IllegalStateException("Milvus 客户端不可用，请检查连接配置");
        }

        ImportResult result = new ImportResult();
        result.filePath = filePath;
        String fileName = Paths.get(filePath).getFileName().toString();

        // 1. 解析 + 清洗 + 分类
        log.info("===== 开始导入: {} =====", filePath);
        List<QaRecord> allRecords = parseJsonl(filePath);
        allRecords.forEach(r -> r.setSourceFile(fileName));
        result.totalRecords = allRecords.size();
        log.info("解析完成: {} 条（已清洗+去噪+分类）", allRecords.size());

        // 2. 分批处理
        long startTime = System.currentTimeMillis();
        int batchSize = ecommerceProperties.getImportConfig().getBatchSize();
        for (int i = 0; i < allRecords.size(); i += batchSize) {
            int end = Math.min(i + batchSize, allRecords.size());
            List<QaRecord> batch = allRecords.subList(i, end);
            try {
                processBatch(batch, result);
            } catch (Exception e) {
                log.error("批次处理失败 (index={}-{})", i, end, e);
                result.failedRecords += batch.size();
            }

            int intervalMs = ecommerceProperties.getImportConfig().getBatchIntervalMs();
            if (intervalMs > 0 && end < allRecords.size()) {
                try {
                    Thread.sleep(intervalMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("导入任务已中断，停止处理后续批次");
                    break;
                }
            }
        }

        // 3. Flush 确保 Milvus 数据落盘
        milvusClient.flush(io.milvus.v2.service.utility.request.FlushReq.builder()
                .collectionNames(List.of(COLLECTION_NAME))
                .build());

        long elapsed = System.currentTimeMillis() - startTime;
        result.elapsedSeconds = elapsed / 1000;
        log.info("===== 导入完成: {} 条已存, {} 条去重跳过, {} 条失败, 耗时 {}s =====",
                result.storedRecords, result.dedupSkipped, result.failedRecords, result.elapsedSeconds);
        return result;
    }

    /** 处理一个批次：去重 → embedding → MySQL → Milvus。 */
    private void processBatch(List<QaRecord> batch, ImportResult result) {
        List<QaRecord> toImport = filterExistingRecords(batch, result);
        if (toImport.isEmpty()) {
            return;
        }

        Map<String, List<Float>> embeddingsByHash = generateEmbeddingsByHash(toImport);
        List<EcommerceQaPair> savedPairs = savePairsWithConflictSkip(toImport);
        writePairsToMilvus(savedPairs, embeddingsByHash);

        result.storedRecords += savedPairs.size();
        log.info("批次完成: {} 条已存 (累计: {})", savedPairs.size(), result.storedRecords);
    }

    private List<QaRecord> filterExistingRecords(List<QaRecord> batch, ImportResult result) {
        if (!ecommerceProperties.getImportConfig().isDeduplicate()) {
            return batch;
        }
        List<String> hashes = batch.stream().map(QaRecord::getRecordHash).toList();
        Set<String> existing = qaPairRepository.findAllByRecordHashIn(hashes).stream()
                .map(EcommerceQaPair::getRecordHash)
                .collect(Collectors.toSet());
        List<QaRecord> filtered = batch.stream()
                .filter(record -> !existing.contains(record.getRecordHash()))
                .toList();
        int skipped = batch.size() - filtered.size();
        if (skipped > 0) {
            result.dedupSkipped += skipped;
            log.info("批次去重跳过 {} 条", skipped);
        }
        return filtered;
    }

    private Map<String, List<Float>> generateEmbeddingsByHash(List<QaRecord> records) {
        List<List<Float>> embeddings = batchGenerateEmbedding(
                records.stream().map(QaRecord::getQaText).toList());
        if (embeddings.size() != records.size()) {
            throw new IllegalStateException("embedding 返回数量不一致: " + embeddings.size() + " vs " + records.size());
        }
        Map<String, List<Float>> byHash = new HashMap<>();
        for (int i = 0; i < records.size(); i++) {
            byHash.put(records.get(i).getRecordHash(), embeddings.get(i));
        }
        return byHash;
    }

    private void writePairsToMilvus(List<EcommerceQaPair> savedPairs,
                                    Map<String, List<Float>> embeddingsByHash) {
        if (savedPairs.isEmpty()) {
            return;
        }
        long timestamp = System.currentTimeMillis() / 1000;
        List<JsonObject> rows = savedPairs.stream()
                .map(pair -> toMilvusRow(pair, embeddingsByHash.get(pair.getRecordHash()), timestamp))
                .filter(java.util.Objects::nonNull)
                .toList();
        if (!rows.isEmpty()) {
            milvusClient.insert(InsertReq.builder().collectionName(COLLECTION_NAME).data(rows).build());
        }
    }

    private JsonObject toMilvusRow(EcommerceQaPair pair, List<Float> embedding, long timestamp) {
        if (embedding == null) {
            return null;
        }
        JsonObject row = new JsonObject();
        row.addProperty("question", pair.getQuestion());
        row.addProperty("answer", pair.getAnswer());
        row.addProperty("qa_text", pair.getQaText());
        row.addProperty("qa_pair_id", pair.getId());
        row.addProperty("category", pair.getCategory());
        row.addProperty("source_file", pair.getSourceFile());
        row.add("embedding", toJsonArray(embedding));
        row.addProperty("ts", timestamp);
        return row;
    }

    /** 保存 QA 对到 MySQL；遇到唯一键冲突（并发/重复）逐条跳过冲突记录。 */
    @Transactional
    protected List<EcommerceQaPair> savePairsWithConflictSkip(List<QaRecord> toImport) {
        List<EcommerceQaPair> savedPairs = new ArrayList<>();
        try {
            List<EcommerceQaPair> pairs = toImport.stream().map(record -> EcommerceQaPair.builder()
                    .question(record.getQuestion())
                    .answer(record.getAnswer())
                    .qaText(record.getQaText())
                    .category(record.getCategory())
                    .sourceFile(record.getSourceFile())
                    .recordHash(record.getRecordHash())
                    .status(1)
                    .build()).toList();
            savedPairs.addAll(qaPairRepository.saveAll(pairs));
            qaPairRepository.flush();
            return savedPairs;
        } catch (DataIntegrityViolationException e) {
            log.warn("批次出现唯一键冲突（重复记录），降级逐条保存并跳过冲突: {}", e.getMessage());
            savedPairs.clear();
            for (QaRecord record : toImport) {
                try {
                    EcommerceQaPair pair = EcommerceQaPair.builder()
                            .question(record.getQuestion())
                            .answer(record.getAnswer())
                            .qaText(record.getQaText())
                            .category(record.getCategory())
                            .sourceFile(record.getSourceFile())
                            .recordHash(record.getRecordHash())
                            .status(1)
                            .build();
                    savedPairs.add(qaPairRepository.save(pair));
                } catch (DataIntegrityViolationException dup) {
                    log.debug("跳过重复记录: {}", record.getRecordHash());
                }
            }
            qaPairRepository.flush();
            return savedPairs;
        }
    }

    private void ensureMilvusWritable() {
        if (aiProperties.getVectorStore().getMilvus().isReadOnly()) {
            throw new IllegalStateException("Milvus is read-only; data import is disabled");
        }
    }

    private JsonArray toJsonArray(List<Float> vector) {
        JsonArray arr = new JsonArray();
        for (Float v : vector) {
            arr.add(v);
        }
        return arr;
    }

    // =============================================
    // 4. 数据模型
    // =============================================

    /** QA 记录（清洗 + 分类后的数据）。 */
    @Getter
    @Builder
    public static class QaRecord {
        private String question;
        private String answer;
        private String qaText;
        private String systemPrompt;
        private String category;
        private String recordHash;
        @Setter
        private String sourceFile;
    }

    /** 导入结果。 */
    @ToString
    public static class ImportResult {
        public String filePath;
        public int totalRecords;
        public int storedRecords;
        public int dedupSkipped;
        public int failedRecords;
        public long elapsedSeconds;
    }
}
