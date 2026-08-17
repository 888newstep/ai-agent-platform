package com.aiagent.ecommerce.application;

import com.aiagent.infrastructure.config.AiProperties;
import com.aiagent.ecommerce.domain.EcommerceQaPair;
import com.aiagent.ecommerce.infrastructure.repository.EcommerceQaPairRepository;
import com.aiagent.ecommerce.config.EcommerceProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.InsertReq;
import jakarta.annotation.PostConstruct;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 电商客服知识库导入服务
 *
 * 功能：读取 JSONL 训练数据 → 数据预处理 → 调用 Ollama bge-m3 生成向量 → 存入云服务器 Milvus
 *       同时写入 MySQL ecommerce_qa_pairs 表（MySQL 为"源"，Milvus 为"索引"）
 *
 * 数据预处理说明：
 * - 原始格式：每条 JSON 包含 system/user/assistant 三条消息
 * - 预处理后：将用户问题和客服回答拼接为 "用户问题：{Q} 客服回答：{A}"
 * - 这样 Embedding 后，当用户提问时可通过语义相似度检索到最匹配的 QA 对
 */
@Slf4j
@Service
public class EcommerceKnowledgeImportService {

    private final MilvusClientV2 milvusClient;
    private final ObjectMapper objectMapper;
    private final EcommerceQaPairRepository qaPairRepository;
    private final EcommerceProperties ecommerceProperties;
    private final AiProperties aiProperties;
    private RestTemplate restTemplate;

    private static final String COLLECTION_NAME = "ecommerce_qa";






    public EcommerceKnowledgeImportService(@Autowired(required = false) MilvusClientV2 milvusClient, ObjectMapper objectMapper,
                                           EcommerceQaPairRepository qaPairRepository,
                                           EcommerceProperties ecommerceProperties,
                                           AiProperties aiProperties) {
        this.milvusClient = milvusClient;
        this.objectMapper = objectMapper;
        this.qaPairRepository = qaPairRepository;
        this.ecommerceProperties = ecommerceProperties;
        this.aiProperties = aiProperties;
    }

    @PostConstruct
    public void init() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(180_000);
        factory.setReadTimeout(600_000);
        this.restTemplate = new RestTemplate(factory);
    }

    // =============================================
    // 1. 数据预处理
    // =============================================

    /**
     * 从 JSONL 文件中解析原始 QA 记录
     */
    public List<QaRecord> parseJsonl(String filePath) throws Exception {
        List<QaRecord> records = new ArrayList<>();
        Path path = Paths.get(filePath);

        if (!Files.exists(path)) {
            throw new IllegalArgumentException("文件不存在: " + filePath);
        }

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(filePath), StandardCharsets.UTF_8))) {

            String line;
            int lineNum = 0;
            while ((line = reader.readLine()) != null) {
                lineNum++;
                line = line.trim();
                if (line.isEmpty()) continue;

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

        log.info("JSONL 解析完成: {} 条有效记录 (总行数: {})", records.size(), records.size());
        return records;
    }

    /**
     * 解析单行 JSON，提取 QA 对
     *
     * 数据预处理关键步骤：
     * 1. 解析 messages 数组
     * 2. 提取 system 指令（用于后续分类，不参与 embedding）
     * 3. 提取 user 提问（客户问题）
     * 4. 提取 assistant 回答（客服回答）
     * 5. 拼接为 embedding 文本
     */
    @SuppressWarnings("unchecked")
    private QaRecord parseJsonLine(String jsonLine, int lineNum) throws JsonProcessingException {
        Map<String, Object> root = objectMapper.readValue(jsonLine, Map.class);
        List<Map<String, Object>> messages = (List<Map<String, Object>>) root.get("messages");

        if (messages == null || messages.size() < 3) {
            log.warn("第 {} 行: messages 字段不足 3 条", lineNum);
            return null;
        }

        String systemContent = "";
        String userContent = "";
        String assistantContent = "";

        for (Map<String, Object> msg : messages) {
            String role = (String) msg.get("role");
            String content = (String) msg.get("content");
            if (content == null) content = "";

            switch (role) {
                case "system" -> systemContent = content;
                case "user" -> userContent = content;
                case "assistant" -> assistantContent = content;
            }
        }

        if (userContent.isEmpty()) {
            log.warn("第 {} 行: user 内容为空，跳过", lineNum);
            return null;
        }

        // 数据预处理：清洗文本，合并多余空白
        String cleanQuestion = cleanText(userContent);
        String cleanAnswer = cleanText(assistantContent);

        // 构建用于 Embedding 的文本
        // 特点：用户问题 + 客服回答 拼接在一起，使得语义相近的问题能检索到对应的回答
        String qaText = "用户问题：" + cleanQuestion + " 客服回答：" + cleanAnswer;

        return QaRecord.builder()
                .question(cleanQuestion)
                .answer(cleanAnswer)
                .qaText(qaText)
                .systemPrompt(systemContent)
                .build();
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

    // =============================================
    // 2. Embedding 生成
    // =============================================

    /**
     * 批量生成向量
     */
    @SuppressWarnings("unchecked")
    public List<List<Float>> batchGenerateEmbedding(List<String> texts) {
        List<String> cleanTexts = texts.stream()
                .map(this::cleanText)
                .toList();

        Map<String, Object> request = new HashMap<>();
        request.put("model", ecommerceProperties.getOllama().getModel());
        request.put("input", cleanTexts);
        request.put("dimensions", ecommerceProperties.getOllama().getDimension());
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("application", "json", StandardCharsets.UTF_8));
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);

        try {
            Map<String, Object> response = restTemplate.postForObject(
                    ecommerceProperties.getOllama().getHost() + "/api/embed",
                    entity,
                    Map.class
            );

            if (response == null) {
                throw new RuntimeException("Ollama 返回空响应");
            }

            List<List<Double>> embeddings = (List<List<Double>>) response.get("embeddings");
            if (embeddings == null || embeddings.isEmpty()) {
                throw new RuntimeException("Ollama 返回的 embeddings 列表为空");
            }

            List<List<Float>> results = new ArrayList<>();
            for (List<Double> emb : embeddings) {
                if (emb == null) {
                    results.add(null);
                } else {
                    results.add(emb.stream().map(Double::floatValue).collect(Collectors.toList()));
                }
            }
            return results;

        } catch (Exception e) {
            log.error("批量生成向量失败 (batch={})", texts.size(), e);
            // 降级：逐条重试
            List<List<Float>> results = new ArrayList<>();
            for (String text : cleanTexts) {
                try {
                    results.add(generateEmbedding(text));
                } catch (Exception ex) {
                    log.error("单条生成向量失败: {}", text.substring(0, Math.min(50, text.length())), ex);
                    results.add(null);
                }
            }
            return results;
        }
    }

    /**
     * 单条文本生成向量
     */
    @SuppressWarnings("unchecked")
    public List<Float> generateEmbedding(String text) {
        String cleanText = cleanText(text);

        Map<String, Object> request = new HashMap<>();
        request.put("model", ecommerceProperties.getOllama().getModel());
        request.put("input", cleanText);
        request.put("dimensions", ecommerceProperties.getOllama().getDimension());
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("application", "json", StandardCharsets.UTF_8));
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);

        Map<String, Object> response = restTemplate.postForObject(
                ecommerceProperties.getOllama().getHost() + "/api/embed",
                entity,
                Map.class
        );

        if (response == null) {
            throw new RuntimeException("Ollama 返回空响应");
        }

        List<List<Double>> embeddings = (List<List<Double>>) response.get("embeddings");
        if (embeddings == null || embeddings.isEmpty()) {
            throw new RuntimeException("Ollama 返回的 embeddings 列表为空");
        }

        List<Double> embedding = embeddings.get(0);
        if (embedding == null) {
            throw new RuntimeException("Ollama 返回的 embedding 向量为空");
        }

        return embedding.stream()
                .map(Double::floatValue)
                .collect(Collectors.toList());
    }

    // =============================================
    // 3. 批量导入 Milvus
    // =============================================

    /**
     * 执行完整的导入流程：解析 → 预处理 → 向量化 → 存入 Milvus → 存入 MySQL
     *
     * 设计原则：
     * - MySQL 是"源"：所有 QA 记录在 MySQL 有完整持久化
     * - Milvus 是"索引"：只存向量 + 检索所需标量字段
     * - 通过 qa_pair_id 关联 MySQL 记录
     *
     * @param filePath JSONL 文件路径
     * @return 导入结果统计
     */
    @Transactional
    public ImportResult importFromFile(String filePath) throws Exception {
        ensureMilvusWritable();
        if (milvusClient == null) {
            throw new IllegalStateException("Milvus 客户端不可用，请检查连接配置");
        }
        ImportResult result = new ImportResult();
        result.filePath = filePath;
        String fileName = Paths.get(filePath).getFileName().toString();

        // 1. 解析 JSONL
        log.info("===== 开始导入: {} =====", filePath);
        List<QaRecord> allRecords = parseJsonl(filePath);
        // 设置 sourceFile
        for (QaRecord record : allRecords) {
            record.setSourceFile(fileName);
        }
        result.totalRecords = allRecords.size();
        log.info("解析完成: {} 条记录", allRecords.size());

        // 2. 分批处理
        long startTime = System.currentTimeMillis();
        AtomicInteger batchCounter = new AtomicInteger(0);

        for (int i = 0; i < allRecords.size(); i += ecommerceProperties.getImportConfig().getBatchSize()) {
            int end = Math.min(i + ecommerceProperties.getImportConfig().getBatchSize(), allRecords.size());
            List<QaRecord> batch = allRecords.subList(i, end);

            try {
                // 2a. 提取 embedding 文本
                List<String> texts = batch.stream()
                        .map(QaRecord::getQaText)
                        .toList();

                // 2b. 批量生成向量
                List<List<Float>> embeddings = batchGenerateEmbedding(texts);

                // 2c. 过滤失败记录
                List<QaRecord> validRecords = new ArrayList<>();
                List<List<Float>> validEmbeddings = new ArrayList<>();
                for (int j = 0; j < embeddings.size(); j++) {
                    if (embeddings.get(j) != null) {
                        validRecords.add(batch.get(j));
                        validEmbeddings.add(embeddings.get(j));
                    }
                }

                if (validRecords.isEmpty()) {
                    result.failedRecords += batch.size();
                    continue;
                }

                // 2d. 先保存到 MySQL（获取自增 ID）
                List<EcommerceQaPair> savedPairs = new ArrayList<>();
                long timestamp = System.currentTimeMillis() / 1000;
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

                // 2e. 构建 Milvus 行数据 (JsonObject 格式)，包含 qa_pair_id
                List<JsonObject> rows = new ArrayList<>();
                for (int j = 0; j < validRecords.size(); j++) {
                    QaRecord record = validRecords.get(j);
                    List<Float> embedding = validEmbeddings.get(j);
                    Long qaPairId = savedPairs.get(j).getId();

                    JsonObject row = new JsonObject();
                    row.addProperty("question", record.getQuestion());
                    row.addProperty("answer", record.getAnswer());
                    row.addProperty("qa_text", record.getQaText());
                    row.addProperty("qa_pair_id", qaPairId);
                    row.addProperty("category", record.getCategory());
                    row.add("embedding", toJsonArray(embedding));
                    row.addProperty("ts", timestamp);
                    rows.add(row);
                }

                // 2f. 批量插入 Milvus
                InsertReq insertReq = InsertReq.builder()
                        .collectionName(COLLECTION_NAME)
                        .data(rows)
                        .build();
                milvusClient.insert(insertReq);

                result.storedRecords += validRecords.size();
                result.failedRecords += (batch.size() - validRecords.size());

                int batchNum = batchCounter.incrementAndGet();
                log.info("批次 {} 完成: {}/{} 条已存 (累计: {}/{})",
                        batchNum, validRecords.size(), batch.size(),
                        result.storedRecords, result.totalRecords);

                // 2g. 批次间隔，避免 Ollama 压力过大
                if (ecommerceProperties.getImportConfig().getBatchIntervalMs() > 0 && i + ecommerceProperties.getImportConfig().getBatchSize() < allRecords.size()) {
                    Thread.sleep(ecommerceProperties.getImportConfig().getBatchIntervalMs());
                }

            } catch (Exception e) {
                log.error("批次处理失败 (index={}-{})", i, end, e);
                result.failedRecords += batch.size();
            }
        }

        // 3. Flush 确保数据落盘
        milvusClient.flush(io.milvus.v2.service.utility.request.FlushReq.builder()
                .collectionNames(List.of(COLLECTION_NAME))
                .build());

        long elapsed = System.currentTimeMillis() - startTime;
        result.elapsedSeconds = elapsed / 1000;
        log.info("===== 导入完成: {} 条已存, {} 条失败, 耗时 {}s =====",
                result.storedRecords, result.failedRecords, result.elapsedSeconds);

        return result;
    }

    private void ensureMilvusWritable() {
        if (aiProperties.getVectorStore().getMilvus().isReadOnly()) {
            throw new IllegalStateException("Milvus is read-only; data import is disabled");
        }
    }

    /**
     * 将 List<Float> 转换为 Gson JsonArray，供 Milvus SDK 使用
     */
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

    /**
     * QA 记录（预处理后的数据）
     */
    public static class QaRecord {
        private String question;       // 用户问题
        private String answer;         // 客服回答
        private String qaText;         // 拼接后的 Embedding 文本
        private String systemPrompt;   // 系统指令（用于参考，不参与 embedding）
        private String category;       // 问题分类（从 system prompt 或文件名推断）
        private String sourceFile;     // 来源文件名

        public static QaRecordBuilder builder() {
            return new QaRecordBuilder();
        }

        public String getQuestion() { return question; }
        public String getAnswer() { return answer; }
        public String getQaText() { return qaText; }
        public String getSystemPrompt() { return systemPrompt; }
        public String getCategory() { return category; }
        public String getSourceFile() { return sourceFile; }

        public void setSourceFile(String sourceFile) { this.sourceFile = sourceFile; }

        public static class QaRecordBuilder {
            private final QaRecord record = new QaRecord();
            public QaRecordBuilder question(String q) { record.question = q; return this; }
            public QaRecordBuilder answer(String a) { record.answer = a; return this; }
            public QaRecordBuilder qaText(String t) { record.qaText = t; return this; }
            public QaRecordBuilder systemPrompt(String s) { record.systemPrompt = s; return this; }
            public QaRecordBuilder category(String c) { record.category = c; return this; }
            public QaRecord build() { return record; }
        }
    }

    /**
     * 导入结果
     */
    public static class ImportResult {
        public String filePath;
        public int totalRecords;
        public int storedRecords;
        public int failedRecords;
        public long elapsedSeconds;

        @Override
        public String toString() {
            return String.format(
                    "ImportResult{file=%s, total=%d, stored=%d, failed=%d, elapsed=%ds}",
                    filePath, totalRecords, storedRecords, failedRecords, elapsedSeconds
            );
        }
    }
}