package com.aiagent.config;

import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import io.milvus.v2.service.database.request.CreateDatabaseReq;
import io.milvus.v2.service.index.request.CreateIndexReq;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Milvus 统一初始化配置
 *
 * 设计原则：
 * - MySQL 是"源"：所有业务数据在 MySQL 有完整记录
 * - Milvus 是"索引"：只存向量 + 检索所需的最小标量字段
 * - 集合创建后统一建 HNSW 索引 + load，确保检索就绪
 *
 * 集合管理策略：
 * ┌──────────────────────────────────────────────────────────────┐
 * │ ecommerce_qa: 由本类手动创建（自定义 schema）                  │
 * │   ├── id: Int64 (autoID) PRIMARY                            │
 * │   ├── question: VarChar(1024)                               │
 * │   ├── answer: VarChar(2048)                                 │
 * │   ├── qa_text: VarChar(3072)                                │
 * │   ├── qa_pair_id: Int64  → 关联 MySQL ecommerce_qa_pairs.id │
 * │   ├── category: VarChar(100)                                │
 * │   ├── embedding: FloatVector(1024)                          │
 * │   └── ts: Int64                                             │
 * ├──────────────────────────────────────────────────────────────┤
 * │ ai_agent_documents: 由 LangChain4j 自动创建管理               │
 * │   （标准 schema: id, text, vector, metadata）                │
 * └──────────────────────────────────────────────────────────────┘
 */
@Slf4j
@Configuration
public class MilvusInitConfig {

    @Value("${ai.vector-store.milvus.host:localhost}")
    private String host;

    @Value("${ai.vector-store.milvus.port:19530}")
    private int port;

    @Value("${ecommerce.milvus.collection:ecommerce_qa}")
    private String qaCollectionName;

    @Value("${ecommerce.milvus.dimension:1024}")
    private int dimension;

    // =============================================
    // 1. MilvusClientV2 Bean（全局单例）
    // =============================================

    @Bean
    public MilvusClientV2 milvusClient() {
        try {
            ConnectConfig config = ConnectConfig.builder()
                    .uri("http://" + host + ":" + port)
                    .build();
            MilvusClientV2 client = new MilvusClientV2(config);

            // 尝试使用 cs_agent 数据库，不存在则创建
            try {
                client.useDatabase("cs_agent");
            } catch (Exception ignored) {
                log.info("数据库 [cs_agent] 不存在，正在创建...");
                client.createDatabase(CreateDatabaseReq.builder()
                        .databaseName("cs_agent")
                        .build());
                client.useDatabase("cs_agent");
                log.info("数据库 [cs_agent] 创建成功");
            }

            log.info("MilvusClientV2 连接成功: {}:{} (database: cs_agent)", host, port);
            return client;
        } catch (Exception e) {
            log.warn("MilvusClientV2 连接失败: {}:{} - {} (应用将以无 Milvus 模式运行)", host, port, e.getMessage());
            return null;
        }
    }

    // =============================================
    // 2. 集合初始化器（启动时自动运行）
    // =============================================

    @Slf4j
    @Component
    public static class CollectionsInitializer {

        private final MilvusClientV2 milvusClient;
        private final int dimension;
        private final String qaCollectionName;

        public CollectionsInitializer(
                @Autowired(required = false) MilvusClientV2 milvusClient,
                @Value("${ecommerce.milvus.collection:ecommerce_qa}") String qaCollectionName,
                @Value("${ecommerce.milvus.dimension:1024}") int dimension) {
            this.milvusClient = milvusClient;
            this.qaCollectionName = qaCollectionName;
            this.dimension = dimension;
        }

        @PostConstruct
        public void init() {
            if (milvusClient == null) {
                log.warn("===== Milvus 客户端不可用，跳过集合初始化 =====");
                return;
            }
            log.info("===== 开始初始化 Milvus 集合 =====");
            createQaCollection();
            log.info("===== Milvus 集合初始化完成 =====");
        }

        // ---------------------------------------------
        // 2a. ecommerce_qa — 电商客服知识库
        // ---------------------------------------------
        private void createQaCollection() {
            if (milvusClient.hasCollection(HasCollectionReq.builder()
                    .collectionName(qaCollectionName).build())) {
                log.info("Collection [{}] 已存在，跳过创建", qaCollectionName);
                return;
            }

            CreateCollectionReq.CollectionSchema schema = milvusClient.createSchema();
            schema.addField(AddFieldReq.builder()
                    .fieldName("id").dataType(DataType.Int64).isPrimaryKey(true).autoID(true)
                    .description("自增主键").build());
            schema.addField(AddFieldReq.builder()
                    .fieldName("question").dataType(DataType.VarChar).maxLength(1024)
                    .description("用户问题").build());
            schema.addField(AddFieldReq.builder()
                    .fieldName("answer").dataType(DataType.VarChar).maxLength(2048)
                    .description("客服回答").build());
            schema.addField(AddFieldReq.builder()
                    .fieldName("qa_text").dataType(DataType.VarChar).maxLength(3072)
                    .description("QA 拼接文本（用于 embedding）").build());
            schema.addField(AddFieldReq.builder()
                    .fieldName("qa_pair_id").dataType(DataType.Int64)
                    .description("关联 MySQL ecommerce_qa_pairs.id").build());
            schema.addField(AddFieldReq.builder()
                    .fieldName("category").dataType(DataType.VarChar).maxLength(100)
                    .description("问题分类：售后/物流/退换货/支付等").build());
            schema.addField(AddFieldReq.builder()
                    .fieldName("embedding").dataType(DataType.FloatVector).dimension(dimension)
                    .description("bge-m3 向量").build());
            schema.addField(AddFieldReq.builder()
                    .fieldName("ts").dataType(DataType.Int64)
                    .description("写入时间戳").build());

            milvusClient.createCollection(CreateCollectionReq.builder()
                    .collectionName(qaCollectionName)
                    .collectionSchema(schema)
                    .description("电商客服知识库（QA 向量 + 元数据）")
                    .build());
            log.info("Collection [{}] 创建成功 (dimension={})", qaCollectionName, dimension);

            // 建 HNSW 索引 + 加载到内存
            IndexParam indexParam = IndexParam.builder()
                    .fieldName("embedding")
                    .metricType(IndexParam.MetricType.COSINE)
                    .indexType(IndexParam.IndexType.HNSW)
                    .extraParams(Map.of("M", 16, "efConstruction", 200))
                    .build();

            milvusClient.createIndex(CreateIndexReq.builder()
                    .collectionName(qaCollectionName)
                    .indexParams(List.of(indexParam))
                    .build());
            log.info("Collection [{}] HNSW 索引创建成功", qaCollectionName);

            milvusClient.loadCollection(LoadCollectionReq.builder()
                    .collectionName(qaCollectionName)
                    .build());
            log.info("Collection [{}] 已加载到内存，检索就绪", qaCollectionName);
        }
    }

    // =============================================
    // 3. 优雅关闭
    // =============================================

    @PreDestroy
    public void destroy() {
        log.info("关闭 Milvus 连接...");
    }
}