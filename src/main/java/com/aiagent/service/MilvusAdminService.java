package com.aiagent.service;

import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.IndexBuildState;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import io.milvus.v2.service.index.request.CreateIndexReq;
import io.milvus.v2.service.index.request.DescribeIndexReq;
import io.milvus.v2.service.utility.request.FlushReq;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Milvus 管理服务
 * 负责：建索引、flush、load
 * 用于数据导入完成后统一构建索引并加载到内存
 */
@Slf4j
@Service
public class MilvusAdminService {

    private final MilvusClientV2 milvusClient;

    /** 业务集合名称 */
    private static final List<String> COLLECTION_NAMES = List.of(
            "ecommerce_qa"
    );

    public MilvusAdminService(@Autowired(required = false) MilvusClientV2 milvusClient) {
        this.milvusClient = milvusClient;
    }

    // =============================================
    // 1. 索引管理
    // =============================================

    /**
     * 为指定集合创建 HNSW 索引
     */
    public void createIndex(String collectionName) {
        if (milvusClient == null) {
            log.warn("Milvus 客户端不可用，跳过建索引");
            return;
        }
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

        log.info("Collection [{}] HNSW 索引创建指令已发出", collectionName);
    }

    /**
     * 异步串行构建所有集合索引
     */
    public CompletableFuture<Void> buildAllIndexesAsync() {
        if (milvusClient == null) {
            log.warn("Milvus 客户端不可用，跳过建索引");
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.runAsync(() -> {
            for (String name : COLLECTION_NAMES) {
                createIndex(name);
                pollIndexProgress(name);
                sleep(2000);
            }
            log.info("全部集合索引构建完成");
        });
    }

    /**
     * 轮询索引构建进度，直到完成
     */
    public void pollIndexProgress(String collectionName) {
        int maxRetries = 120;
        for (int i = 0; i < maxRetries; i++) {
            try {
                DescribeIndexReq req = DescribeIndexReq.builder()
                        .collectionName(collectionName)
                        .build();
                var resp = milvusClient.describeIndex(req);
                var indexDesc = resp.getIndexDescByFieldName("embedding");
                if (indexDesc == null) {
                    log.info("[{}] 索引描述信息尚未就绪 ({}/{})", collectionName, i + 1, maxRetries);
                    sleep(10000);
                    continue;
                }
                IndexBuildState state = indexDesc.getIndexState();
                long progress = indexDesc.getTotalRows() > 0
                        ? indexDesc.getIndexedRows() * 100 / indexDesc.getTotalRows()
                        : 0;
                log.info("[{}] 索引状态: {} 进度: {}% (indexedRows={}/{})",
                        collectionName, state, progress,
                        indexDesc.getIndexedRows(), indexDesc.getTotalRows());

                if (state == IndexBuildState.Finished) {
                    log.info("[{}] 索引构建完成", collectionName);
                    return;
                }
                if (state == IndexBuildState.Failed) {
                    log.error("[{}] 索引构建失败: {}", collectionName, indexDesc.getIndexFailedReason());
                    return;
                }
            } catch (Exception e) {
                log.warn("[{}] 查询索引状态异常: {}", collectionName, e.getMessage());
            }
            sleep(10000);
        }
        log.warn("[{}] 索引构建监控超时，请通过 Attu 手动确认状态", collectionName);
    }

    // =============================================
    // 2. Flush
    // =============================================

    /**
     * 刷新所有集合，落盘数据
     */
    public void flushAll() {
        if (milvusClient == null) {
            log.warn("Milvus 客户端不可用，跳过 flush");
            return;
        }
        for (String name : COLLECTION_NAMES) {
            try {
                milvusClient.flush(FlushReq.builder()
                        .collectionNames(List.of(name))
                        .build());
                log.info("Collection [{}] flush 完成", name);
            } catch (Exception e) {
                log.warn("Collection [{}] flush 失败: {}", name, e.getMessage());
            }
        }
    }

    // =============================================
    // 3. Load / Release
    // =============================================

    /**
     * 加载集合到内存，启用检索
     */
    public void loadCollection(String collectionName) {
        if (milvusClient == null) return;
        milvusClient.loadCollection(LoadCollectionReq.builder()
                .collectionName(collectionName)
                .build());
        log.info("Collection [{}] 已加载到内存", collectionName);
    }

    /**
     * 加载所有集合
     */
    public void loadAllCollections() {
        if (milvusClient == null) return;
        for (String name : COLLECTION_NAMES) {
            loadCollection(name);
        }
        log.info("所有集合已加载到内存");
    }

    // =============================================
    // 辅助
    // =============================================

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}