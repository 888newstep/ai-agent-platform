package com.aiagent.retrieval;

import com.aiagent.cache.RagCacheService;
import com.aiagent.config.AiProperties;
import com.aiagent.document.DocumentChunk;
import com.aiagent.document.DocumentService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 多路召回 + RRF 融合排序服务
 *
 * <p>核心流程：
 * <ol>
 *   <li>先查 RAG 缓存（Redis），命中直接返回</li>
 *   <li>向量检索路：使用 Milvus 进行语义相似度检索</li>
 *   <li>关键词检索路：在向量检索结果上执行 BM25 精确匹配（复用候选池，避免二次查 Milvus）</li>
 *   <li>RRF 融合：将两路结果使用倒数排名融合算法合并</li>
 *   <li>结果写入 RAG 缓存</li>
 * </ol>
 *
 * <p>RRF 公式：score(d) = Σ 1/(k + rank_i(d))
 * 其中 rank_i(d) 是文档 d 在第 i 路检索中的排名，k 是常数（默认 60）
 *
 * <p>面试价值：
 * <ul>
 *   <li>Q143 如何提高 RAG 召回率 — 多路召回 + RRF 是标准方案</li>
 *   <li>RAG 缓存：相同查询复用检索结果，避免重复查 Milvus</li>
 *   <li>复用候选池：消除冗余向量检索，一次查 Milvus 供两路使用</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MultiRecallService {

    private final DocumentService documentService;
    private final AiProperties aiProperties;
    private final RagCacheService ragCacheService;

    /** RRF 常数 k */
    private static final int RRF_K = 60;

    /** 每路召回数量（多于最终结果，便于融合排序） */
    private static final int PER_ROUTE_TOP_K = 20;

    /** BM25 候选池大小（从向量检索结果中取更多条供 BM25 排序） */
    private static final int BM25_CANDIDATE_POOL = 50;

    @PostConstruct
    public void init() {
        log.info("多路召回服务初始化完成 (RRF k={}, 每路召回={}, BM25候选池={})",
                RRF_K, PER_ROUTE_TOP_K, BM25_CANDIDATE_POOL);
    }

    /**
     * 执行多路召回 + RRF 融合
     *
     * @param query 用户查询
     * @param topK  最终返回 topK 条结果
     * @return 融合排序后的文档片段
     */
    public List<DocumentChunk> search(String query, int topK) {
        // 0. 尝试 RAG 缓存（相同查询直接返回缓存结果）
        List<DocumentChunk> cached = ragCacheService.getCachedResults(query);
        if (cached != null) {
            log.info("RAG 缓存命中: query={}, 返回 {} 条", query, cached.size());
            return cached.size() > topK ? cached.subList(0, topK) : cached;
        }

        // 1. 向量检索（一次查询，供两路使用）
        //    取 BM25_CANDIDATE_POOL 条，前 PER_ROUTE_TOP_K 条给向量路，全部给 BM25 候选池
        List<DocumentChunk> vectorCandidates = vectorSearch(query, BM25_CANDIDATE_POOL);
        log.info("向量检索候选池 {} 条", vectorCandidates.size());

        List<DocumentChunk> vectorResults = vectorCandidates.size() > PER_ROUTE_TOP_K
                ? vectorCandidates.subList(0, PER_ROUTE_TOP_K)
                : vectorCandidates;

        // 2. 关键词检索（在向量候选池上执行 BM25，无需二次查 Milvus）
        List<DocumentChunk> bm25Results = bm25SearchOnCandidates(query, vectorCandidates, PER_ROUTE_TOP_K);
        log.info("BM25 检索召回 {} 条", bm25Results.size());

        // 3. RRF 融合
        List<DocumentChunk> fusedResults = rrfFuse(List.of(vectorResults, bm25Results), topK);
        log.info("RRF 融合后返回 {} 条", fusedResults.size());

        // 4. 写入 RAG 缓存
        ragCacheService.cacheResults(query, fusedResults);

        return fusedResults;
    }

    /**
     * 向量检索（复用现有的 DocumentService）
     */
    private List<DocumentChunk> vectorSearch(String query, int topK) {
        try {
            AiProperties.Rag ragConfig = aiProperties.getRag();
            return documentService.searchSimilar(query, topK, ragConfig.getSimilarityThreshold());
        } catch (Exception e) {
            log.warn("向量检索失败: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 在向量候选池上执行 BM25 关键词检索
     * <p>
     * 复用向量检索结果作为候选池，避免二次查询 Milvus。
     * 如果候选池为空，回退到独立向量检索。
     */
    private List<DocumentChunk> bm25SearchOnCandidates(String query, List<DocumentChunk> candidates, int topK) {
        if (candidates == null || candidates.isEmpty()) {
            // 回退：独立查一次 Milvus
            log.warn("BM25 候选池为空，回退到独立向量检索");
            candidates = vectorSearch(query, BM25_CANDIDATE_POOL);
            if (candidates.isEmpty()) {
                return List.of();
            }
        }

        try {
            Bm25Search bm25 = new Bm25Search(candidates);
            return bm25.search(query, topK);
        } catch (Exception e) {
            log.warn("BM25 检索失败: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * RRF 倒数排名融合
     *
     * @param lists 多路召回结果列表
     * @param topK  最终返回的结果数
     * @return 融合排序后的结果
     */
    private List<DocumentChunk> rrfFuse(List<List<DocumentChunk>> lists, int topK) {
        // 文档 ID → RRF 分数
        Map<String, Double> rrfScores = new HashMap<>();
        // 文档 ID → 文档对象
        Map<String, DocumentChunk> docMap = new HashMap<>();

        for (List<DocumentChunk> list : lists) {
            for (int rank = 0; rank < list.size(); rank++) {
                DocumentChunk doc = list.get(rank);
                String docId = doc.getId();
                docMap.putIfAbsent(docId, doc);
                // RRF 分数累加
                rrfScores.merge(docId, 1.0 / (RRF_K + rank + 1), Double::sum);
            }
        }

        // 按 RRF 分数降序排列
        return rrfScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(entry -> {
                    DocumentChunk doc = docMap.get(entry.getKey());
                    return DocumentChunk.builder()
                            .id(doc.getId())
                            .content(doc.getContent())
                            .score(entry.getValue())
                            .metadata(doc.getMetadata())
                            .build();
                })
                .collect(Collectors.toList());
    }
}