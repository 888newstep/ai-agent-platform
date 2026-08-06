package com.aiagent.rag.application;

import com.aiagent.infrastructure.cache.RagCacheService;
import com.aiagent.infrastructure.config.AiProperties;
import com.aiagent.knowledge.domain.RetrievalChunk;
import com.aiagent.knowledge.application.DocumentService;
import com.aiagent.infrastructure.metrics.PlatformMetricsService;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MultiRecallService {

    private static final int RRF_K = 60;
    private static final int PER_ROUTE_TOP_K = 20;
    private static final int BM25_CANDIDATE_POOL = 50;

    private final DocumentService documentService;
    private final AiProperties aiProperties;
    private final RagCacheService ragCacheService;
    private final PlatformMetricsService metricsService;

    @PostConstruct
    public void init() {
        log.info("Multi-recall service initialized (RRF k={}, perRouteTopK={}, bm25Pool={})",
                RRF_K, PER_ROUTE_TOP_K, BM25_CANDIDATE_POOL);
    }

    public List<RetrievalChunk> search(String query, int topK) {
        Timer.Sample sample = metricsService.startSample();
        boolean cacheHit = false;
        int resultCount = 0;

        try {
            List<RetrievalChunk> cached = ragCacheService.getCachedResults(query);
            if (cached != null) {
                cacheHit = true;
                resultCount = Math.min(cached.size(), topK);
                log.info("RAG cache hit for query={}, size={}", query, cached.size());
                return cached.size() > topK ? cached.subList(0, topK) : cached;
            }

            List<RetrievalChunk> vectorCandidates = vectorSearch(query, BM25_CANDIDATE_POOL);
            log.info("Vector candidate size={}", vectorCandidates.size());

            List<RetrievalChunk> vectorResults = vectorCandidates.size() > PER_ROUTE_TOP_K
                    ? vectorCandidates.subList(0, PER_ROUTE_TOP_K)
                    : vectorCandidates;

            List<RetrievalChunk> bm25Results = bm25SearchOnCandidates(query, vectorCandidates, PER_ROUTE_TOP_K);
            log.info("BM25 result size={}", bm25Results.size());

            List<RetrievalChunk> fusedResults = rrfFuse(List.of(vectorResults, bm25Results), topK);
            resultCount = fusedResults.size();
            log.info("RRF fused result size={}", fusedResults.size());

            ragCacheService.cacheResults(query, fusedResults);
            return fusedResults;
        } finally {
            metricsService.recordRagSearch(cacheHit, resultCount, sample);
        }
    }

    private List<RetrievalChunk> vectorSearch(String query, int topK) {
        try {
            AiProperties.Rag ragConfig = aiProperties.getRag();
            return documentService.searchSimilar(query, topK, ragConfig.getSimilarityThreshold());
        } catch (Exception e) {
            log.warn("Vector search failed: {}", e.getMessage());
            return List.of();
        }
    }

    private List<RetrievalChunk> bm25SearchOnCandidates(String query, List<RetrievalChunk> candidates, int topK) {
        if (candidates == null || candidates.isEmpty()) {
            log.warn("BM25 candidate pool is empty, falling back to vector search");
            candidates = vectorSearch(query, BM25_CANDIDATE_POOL);
            if (candidates.isEmpty()) {
                return List.of();
            }
        }

        try {
            Bm25Search bm25 = new Bm25Search(candidates);
            return bm25.search(query, topK);
        } catch (Exception e) {
            log.warn("BM25 search failed: {}", e.getMessage());
            return List.of();
        }
    }

    private List<RetrievalChunk> rrfFuse(List<List<RetrievalChunk>> lists, int topK) {
        Map<String, Double> rrfScores = new HashMap<>();
        Map<String, RetrievalChunk> docMap = new HashMap<>();

        for (List<RetrievalChunk> list : lists) {
            for (int rank = 0; rank < list.size(); rank++) {
                RetrievalChunk doc = list.get(rank);
                String docId = doc.getId();
                docMap.putIfAbsent(docId, doc);
                rrfScores.merge(docId, 1.0 / (RRF_K + rank + 1), Double::sum);
            }
        }

        return rrfScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(entry -> {
                    RetrievalChunk doc = docMap.get(entry.getKey());
                    return RetrievalChunk.builder()
                            .id(doc.getId())
                            .content(doc.getContent())
                            .score(entry.getValue())
                            .metadata(doc.getMetadata())
                            .build();
                })
                .collect(Collectors.toList());
    }
}
