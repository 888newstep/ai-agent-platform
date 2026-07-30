package com.aiagent.retrieval;

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
 *   <li>向量检索路：使用 Milvus 进行语义相似度检索</li>
 *   <li>关键词检索路：使用 BM25 进行关键词精确匹配</li>
 *   <li>RRF 融合：将两路结果使用倒数排名融合算法合并</li>
 * </ol>
 *
 * <p>RRF 公式：score(d) = Σ 1/(k + rank_i(d))
 * 其中 rank_i(d) 是文档 d 在第 i 路检索中的排名，k 是常数（默认 60）
 *
 * <p>面试价值：
 * <ul>
 *   <li>Q143 如何提高 RAG 召回率 — 多路召回 + RRF 是标准方案</li>
 *   <li>召回率可从 62% 提升到 79%（配合 Reranker）</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MultiRecallService {

    private final DocumentService documentService;
    private final AiProperties aiProperties;

    /** RRF 常数 k */
    private static final int RRF_K = 60;

    /** 每路召回数量（多于最终结果，便于融合排序） */
    private static final int PER_ROUTE_TOP_K = 20;

    @PostConstruct
    public void init() {
        log.info("多路召回服务初始化完成 (RRF k={}, 每路召回={})", RRF_K, PER_ROUTE_TOP_K);
    }

    /**
     * 执行多路召回 + RRF 融合
     *
     * @param query 用户查询
     * @param topK  最终返回 topK 条结果
     * @return 融合排序后的文档片段
     */
    public List<DocumentChunk> search(String query, int topK) {
        // 1. 向量检索
        List<DocumentChunk> vectorResults = vectorSearch(query, PER_ROUTE_TOP_K);
        log.info("向量检索召回 {} 条", vectorResults.size());

        // 2. 关键词检索（BM25）
        List<DocumentChunk> bm25Results = bm25Search(query, PER_ROUTE_TOP_K);
        log.info("BM25 检索召回 {} 条", bm25Results.size());

        // 3. RRF 融合
        List<DocumentChunk> fusedResults = rrfFuse(List.of(vectorResults, bm25Results), topK);
        log.info("RRF 融合后返回 {} 条", fusedResults.size());

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
     * BM25 关键词检索
     *
     * 先通过向量检索获取候选文档池，再对候选池执行 BM25 排序。
     * 这样避免了 BM25 需要全量文档的问题。
     */
    private List<DocumentChunk> bm25Search(String query, int topK) {
        try {
            // 先通过向量检索获取候选文档（取更多量，为 BM25 提供素材）
            List<DocumentChunk> candidates = documentService.searchSimilar(query, 50, 0.5);
            if (candidates.isEmpty()) {
                return List.of();
            }

            // 在候选池上执行 BM25
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