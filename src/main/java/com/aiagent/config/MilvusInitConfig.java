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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Configuration
public class MilvusInitConfig {

    @Value("${ai.vector-store.milvus.host:localhost}")
    private String host;

    @Value("${ai.vector-store.milvus.port:19530}")
    private int port;

    @Value("${ai.vector-store.milvus.connection-timeout-ms:2000}")
    private int connectionTimeoutMs;

    @Value("${ecommerce.milvus.collection:ecommerce_qa}")
    private String qaCollectionName;

    @Value("${ecommerce.milvus.dimension:1024}")
    private int dimension;

    @Bean
    public MilvusClientV2 milvusClient() {
        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "milvus-client-init");
            thread.setDaemon(true);
            return thread;
        });

        try {
            Future<MilvusClientV2> future = executor.submit(() -> {
                ConnectConfig config = ConnectConfig.builder()
                        .uri("http://" + host + ":" + port)
                        .build();
                MilvusClientV2 client = new MilvusClientV2(config);

                try {
                    client.useDatabase("cs_agent");
                } catch (Exception ignored) {
                    log.info("Database [cs_agent] does not exist, creating it now");
                    client.createDatabase(CreateDatabaseReq.builder()
                            .databaseName("cs_agent")
                            .build());
                    client.useDatabase("cs_agent");
                    log.info("Database [cs_agent] created successfully");
                }

                return client;
            });

            MilvusClientV2 client = future.get(connectionTimeoutMs, TimeUnit.MILLISECONDS);
            log.info("MilvusClientV2 connected: {}:{} (database: cs_agent)", host, port);
            return client;
        } catch (TimeoutException e) {
            log.warn("MilvusClientV2 connection timed out after {}ms; starting without Milvus", connectionTimeoutMs);
        } catch (Exception e) {
            log.warn("MilvusClientV2 connection failed: {}:{} - {} (starting without Milvus)", host, port, extractMessage(e));
        } finally {
            executor.shutdownNow();
        }
        return null;
    }

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
                log.warn("Milvus client unavailable, skipping collection initialization");
                return;
            }
            log.info("Initializing Milvus collections");
            createQaCollection();
            log.info("Milvus collection initialization finished");
        }

        private void createQaCollection() {
            if (milvusClient.hasCollection(HasCollectionReq.builder()
                    .collectionName(qaCollectionName)
                    .build())) {
                log.info("Collection [{}] already exists, skipping creation", qaCollectionName);
                return;
            }

            CreateCollectionReq.CollectionSchema schema = milvusClient.createSchema();
            schema.addField(AddFieldReq.builder()
                    .fieldName("id")
                    .dataType(DataType.Int64)
                    .isPrimaryKey(true)
                    .autoID(true)
                    .description("Primary key")
                    .build());
            schema.addField(AddFieldReq.builder()
                    .fieldName("question")
                    .dataType(DataType.VarChar)
                    .maxLength(1024)
                    .description("Question")
                    .build());
            schema.addField(AddFieldReq.builder()
                    .fieldName("answer")
                    .dataType(DataType.VarChar)
                    .maxLength(2048)
                    .description("Answer")
                    .build());
            schema.addField(AddFieldReq.builder()
                    .fieldName("qa_text")
                    .dataType(DataType.VarChar)
                    .maxLength(3072)
                    .description("Joined QA text")
                    .build());
            schema.addField(AddFieldReq.builder()
                    .fieldName("qa_pair_id")
                    .dataType(DataType.Int64)
                    .description("MySQL ecommerce_qa_pairs.id")
                    .build());
            schema.addField(AddFieldReq.builder()
                    .fieldName("category")
                    .dataType(DataType.VarChar)
                    .maxLength(100)
                    .description("Category")
                    .build());
            schema.addField(AddFieldReq.builder()
                    .fieldName("embedding")
                    .dataType(DataType.FloatVector)
                    .dimension(dimension)
                    .description("Embedding vector")
                    .build());
            schema.addField(AddFieldReq.builder()
                    .fieldName("ts")
                    .dataType(DataType.Int64)
                    .description("Timestamp")
                    .build());

            milvusClient.createCollection(CreateCollectionReq.builder()
                    .collectionName(qaCollectionName)
                    .collectionSchema(schema)
                    .description("Ecommerce QA knowledge base")
                    .build());
            log.info("Collection [{}] created successfully (dimension={})", qaCollectionName, dimension);

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
            log.info("Collection [{}] HNSW index created successfully", qaCollectionName);

            milvusClient.loadCollection(LoadCollectionReq.builder()
                    .collectionName(qaCollectionName)
                    .build());
            log.info("Collection [{}] loaded into memory", qaCollectionName);
        }
    }

    @PreDestroy
    public void destroy() {
        log.info("Closing Milvus initialization context");
    }

    private String extractMessage(Exception exception) {
        Throwable cause = exception.getCause();
        return cause != null && cause.getMessage() != null ? cause.getMessage() : exception.getMessage();
    }
}
