package com.aiagent.infrastructure.config;

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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "ai.vector-store", name = "type", havingValue = "milvus", matchIfMissing = true)
@RequiredArgsConstructor
public class MilvusInitConfig {

    private final AiProperties aiProperties;

    @Bean
    public MilvusClientV2 milvusClient() {
        AiProperties.Milvus milvusConfig = aiProperties.getVectorStore().getMilvus();
        if (!isTcpReachable(milvusConfig)) {
            log.warn("Milvus TCP endpoint is unreachable: {}:{}; starting without Milvus",
                    milvusConfig.getHost(), milvusConfig.getPort());
            return null;
        }
        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "milvus-client-init");
            thread.setDaemon(true);
            return thread;
        });

        try {
            Future<MilvusClientV2> future = executor.submit(() -> {
                ConnectConfig config = ConnectConfig.builder()
                        .uri("http://" + milvusConfig.getHost() + ":" + milvusConfig.getPort())
                        .build();
                MilvusClientV2 client = new MilvusClientV2(config);

                String databaseName = milvusConfig.getDatabaseName();
                if (StringUtils.hasText(databaseName)) {
                    try {
                        client.useDatabase(databaseName);
                    } catch (Exception exception) {
                        if (milvusConfig.isReadOnly()) {
                            throw new IllegalStateException(
                                    "Milvus database [" + databaseName + "] is unavailable in read-only mode",
                                    exception);
                        }
                        log.info("Database [{}] does not exist, creating it now", databaseName);
                        client.createDatabase(CreateDatabaseReq.builder()
                                .databaseName(databaseName)
                                .build());
                        client.useDatabase(databaseName);
                        log.info("Database [{}] created successfully", databaseName);
                    }
                }

                return client;
            });

            MilvusClientV2 client = future.get(milvusConfig.getConnectionTimeoutMs(), TimeUnit.MILLISECONDS);
            if (client == null) {
                log.warn("MilvusClientV2 unavailable after initialization");
                return null;
            }
            log.info("MilvusClientV2 connected: {}:{} (database: {}, readOnly: {})",
                    milvusConfig.getHost(),
                    milvusConfig.getPort(),
                    milvusConfig.getDatabaseName(),
                    milvusConfig.isReadOnly());
            return client;
        } catch (TimeoutException e) {
            log.warn("MilvusClientV2 connection timed out after {}ms; starting without Milvus", 
                    milvusConfig.getConnectionTimeoutMs());
        } catch (Exception e) {
            log.warn("MilvusClientV2 connection failed: {}:{} - {} (starting without Milvus)", 
                    milvusConfig.getHost(),
                    milvusConfig.getPort(),
                    extractMessage(e));
        } finally {
            executor.shutdownNow();
        }
        return null;
    }

    private boolean isTcpReachable(AiProperties.Milvus config) {
        try (Socket socket = new Socket()) {
            socket.connect(
                    new InetSocketAddress(config.getHost(), config.getPort()),
                    config.getConnectionTimeoutMs());
            return true;
        } catch (IOException exception) {
            return false;
        }
    }

    @Slf4j
    @Component
    @ConditionalOnProperty(prefix = "ai.vector-store", name = "type", havingValue = "milvus", matchIfMissing = true)
    public static class CollectionsInitializer {

        private final MilvusClientV2 milvusClient;
        private final AiProperties aiProperties;

        public CollectionsInitializer(
                @Autowired(required = false) MilvusClientV2 milvusClient,
                AiProperties aiProperties) {
            this.milvusClient = milvusClient;
            this.aiProperties = aiProperties;
        }

        @PostConstruct
        public void init() {
            if (milvusClient == null) {
                log.warn("Milvus client unavailable, skipping collection initialization");
                return;
            }
            if (!"qa".equalsIgnoreCase(aiProperties.getVectorStore().getMode())) {
                log.info("Skipping QA collection initialization for vector-store mode [{}]",
                        aiProperties.getVectorStore().getMode());
                return;
            }
            log.info("Initializing Milvus collections");
            createQaCollection();
            log.info("Milvus collection initialization finished");
        }

        private void createQaCollection() {
            String collectionName = aiProperties.getVectorStore().getMilvus().getCollectionName();
            int dimension = aiProperties.getVectorStore().getMilvus().getDimension();
            
            boolean exists = milvusClient.hasCollection(HasCollectionReq.builder()
                    .collectionName(collectionName)
                    .build());
            if (exists) {
                log.info("Collection [{}] already exists, skipping creation", collectionName);
                return;
            }
            if (readOnly()) {
                log.warn("Collection [{}] does not exist; read-only mode forbids automatic creation", collectionName);
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
                    .fieldName("source_file")
                    .dataType(DataType.VarChar)
                    .maxLength(255)
                    .description("Source training file name")
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
                    .collectionName(collectionName)
                    .collectionSchema(schema)
                    .description("Ecommerce QA knowledge base")
                    .build());
            log.info("Collection [{}] created successfully (dimension={})", collectionName, dimension);

            IndexParam indexParam = IndexParam.builder()
                    .fieldName("embedding")
                    .metricType(IndexParam.MetricType.COSINE)
                    .indexType(IndexParam.IndexType.HNSW)
                    .extraParams(Map.of("M", 16, "efConstruction", 200))
                    .build();

            milvusClient.createIndex(CreateIndexReq.builder()
                    .collectionName(collectionName)
                    .indexParams(List.of(indexParam))
                    .build());
            log.info("Collection [{}] HNSW index created successfully", collectionName);

            milvusClient.loadCollection(LoadCollectionReq.builder()
                    .collectionName(collectionName)
                    .build());
            log.info("Collection [{}] loaded into memory", collectionName);
        }

        private boolean readOnly() {
            return aiProperties.getVectorStore().getMilvus().isReadOnly();
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
