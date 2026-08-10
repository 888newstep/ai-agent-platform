package com.aiagent.rag.application;

import com.aiagent.infrastructure.cache.RagCacheService;
import com.aiagent.infrastructure.config.AiProperties;
import com.aiagent.infrastructure.metrics.PlatformMetricsService;
import com.aiagent.knowledge.application.DocumentService;
import com.aiagent.knowledge.domain.RetrievalChunk;
import com.aiagent.knowledge.infrastructure.vectorstore.VectorStoreService;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MultiRecallService {

    private static final int RRF_K = 60;
    private static final int PER_ROUTE_TOP_K = 20;
    private static final int BM25_CANDIDATE_POOL = 50;
    private static final int BM25_CORPUS_MAX_DOCS = 5000;
    private static final long CORPUS_INDEX_TTL_MS = 5 * 60 * 1000L;

    private final DocumentService documentService;
    private final AiProperties aiProperties;
    private final RagCacheService ragCacheService;
    private final PlatformMetricsService metricsService;
    private final VectorStoreService vectorStoreService;
    private final Object indexLock = new Object();
    private volatile Bm25IndexHolder corpusBm25;

    @PostConstruct
    public void init() {
        log.info("Multi-recall service initialized (RRF k={}, perRouteTopK={}, bm25Pool={}, corpusMaxDocs={})",
                RRF_K, PER_ROUTE_TOP_K, BM25_CANDIDATE_POOL, BM25_CORPUS_MAX_DOCS);
    }

    public List<RetrievalChunk> search(String query, int topK) {
        AiProperties.Rag ragConfig = aiProperties.getRag();
        return search(query, SearchOptions.builder()
                .topK(topK)
                .similarityThreshold(ragConfig.getSimilarityThreshold())
                .hybridSearch(ragConfig.isEnableHybridSearch())
                .cacheEnabled(true)
                .build());
    }

    public List<RetrievalChunk> search(String query, SearchOptions requestedOptions) {
        SearchOptions options = resolveOptions(requestedOptions);
        Timer.Sample sample = metricsService.startSample();
        boolean cacheHit = false;
        int resultCount = 0;
        String cacheKeyMaterial = buildCacheKeyMaterial(query, options);

        try {
            if (options.isCacheEnabled()) {
                List<RetrievalChunk> cached = ragCacheService.getCachedResults(cacheKeyMaterial);
                if (cached != null) {
                    cacheHit = true;
                    resultCount = Math.min(cached.size(), options.getTopK());
                    log.debug("RAG cache hit for query={}, size={}, hybrid={}, threshold={}",
                            query, cached.size(), options.isHybridSearch(), options.getSimilarityThreshold());
                    return cached.size() > options.getTopK() ? cached.subList(0, options.getTopK()) : cached;
                }
            }

            List<RetrievalChunk> finalResults;
            if (options.isHybridSearch()) {
                List<RetrievalChunk> vectorResults = vectorSearch(query, PER_ROUTE_TOP_K, options.getSimilarityThreshold());
                log.debug("Vector result size={}", vectorResults.size());

                List<RetrievalChunk> bm25Results = bm25SearchCorpus(query, PER_ROUTE_TOP_K);
                if (bm25Results.isEmpty()) {
                    log.debug("Corpus BM25 unavailable, falling back to candidate-pool BM25");
                    List<RetrievalChunk> pool = vectorResults.size() >= BM25_CANDIDATE_POOL
                            ? vectorResults
                            : vectorSearch(query, BM25_CANDIDATE_POOL, options.getSimilarityThreshold());
                    bm25Results = bm25SearchOnCandidates(query, pool, PER_ROUTE_TOP_K, options.getSimilarityThreshold());
                }
                log.debug("BM25 result size={}", bm25Results.size());

                finalResults = rrfFuse(List.of(vectorResults, bm25Results), options.getTopK());
                log.debug("RRF fused result size={}", finalResults.size());
            } else {
                finalResults = annotateVectorOnly(
                        vectorSearch(query, options.getTopK(), options.getSimilarityThreshold()),
                        options.getTopK());
                log.debug("Vector-only result size={}", finalResults.size());
            }

            resultCount = finalResults.size();
            if (options.isCacheEnabled()) {
                ragCacheService.cacheResults(cacheKeyMaterial, finalResults);
            }
            return finalResults;
        } finally {
            metricsService.recordRagSearch(cacheHit, resultCount, sample);
        }
    }

    private SearchOptions resolveOptions(SearchOptions options) {
        AiProperties.Rag ragConfig = aiProperties.getRag();
        if (options == null) {
            return SearchOptions.builder()
                    .topK(ragConfig.getTopK())
                    .similarityThreshold(ragConfig.getSimilarityThreshold())
                    .hybridSearch(ragConfig.isEnableHybridSearch())
                    .cacheEnabled(true)
                    .build();
        }

        return SearchOptions.builder()
                .topK(options.getTopK() > 0 ? options.getTopK() : ragConfig.getTopK())
                .similarityThreshold(options.getSimilarityThreshold() > 0 ? options.getSimilarityThreshold() : ragConfig.getSimilarityThreshold())
                .hybridSearch(options.isHybridSearch())
                .cacheEnabled(options.isCacheEnabled())
                .build();
    }

    private String buildCacheKeyMaterial(String query, SearchOptions options) {
        return query
                + "|topK=" + options.getTopK()
                + "|threshold=" + String.format(Locale.ROOT, "%.4f", options.getSimilarityThreshold())
                + "|hybrid=" + options.isHybridSearch();
    }

    private List<RetrievalChunk> vectorSearch(String query, int topK, double threshold) {
        try {
            return documentService.searchSimilar(query, topK, threshold);
        } catch (Exception e) {
            log.warn("Vector search failed: {}", e.getMessage());
            return List.of();
        }
    }

    private List<RetrievalChunk> bm25SearchOnCandidates(String query,
                                                        List<RetrievalChunk> candidates,
                                                        int topK,
                                                        double similarityThreshold) {
        if (candidates == null || candidates.isEmpty()) {
            log.warn("BM25 candidate pool is empty, falling back to vector search");
            candidates = vectorSearch(query, BM25_CANDIDATE_POOL, similarityThreshold);
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

    private List<RetrievalChunk> bm25SearchCorpus(String query, int topK) {
        Bm25Search index = corpusBm25Index();
        if (index == null) {
            return List.of();
        }
        try {
            return index.search(query, topK);
        } catch (Exception e) {
            log.warn("Corpus BM25 search failed: {}", e.getMessage());
            return List.of();
        }
    }

    private Bm25Search corpusBm25Index() {
        Bm25IndexHolder holder = corpusBm25;
        if (holder != null && !holder.isStale()) {
            return holder.index;
        }
        synchronized (indexLock) {
            holder = corpusBm25;
            if (holder != null && !holder.isStale()) {
                return holder.index;
            }
            List<RetrievalChunk> corpus;
            try {
                corpus = vectorStoreService.fetchAllChunks(BM25_CORPUS_MAX_DOCS);
            } catch (Exception e) {
                log.warn("Failed to load corpus for BM25 index: {}", e.getMessage());
                return null;
            }
            if (corpus == null || corpus.isEmpty()) {
                log.warn("Corpus is empty; corpus BM25 unavailable");
                return null;
            }
            Bm25Search built = new Bm25Search(corpus);
            corpusBm25 = new Bm25IndexHolder(built, System.currentTimeMillis());
            log.info("Rebuilt corpus BM25 index over {} chunks", corpus.size());
            return built;
        }
    }

    private List<RetrievalChunk> annotateVectorOnly(List<RetrievalChunk> vectorResults, int topK) {
        List<RetrievalChunk> limited = vectorResults.size() > topK
                ? vectorResults.subList(0, topK)
                : vectorResults;

        java.util.ArrayList<RetrievalChunk> annotated = new java.util.ArrayList<>(limited.size());
        for (int index = 0; index < limited.size(); index++) {
            RetrievalChunk chunk = limited.get(index);
            Map<String, Object> metadata = new LinkedHashMap<>();
            if (chunk.getMetadata() != null) {
                metadata.putAll(chunk.getMetadata());
            }
            metadata.put("retrievalSource", "vector_only");
            metadata.put("vectorRank", index + 1);
            annotated.add(RetrievalChunk.builder()
                    .id(chunk.getId())
                    .content(chunk.getContent())
                    .score(chunk.getScore())
                    .metadata(metadata)
                    .build());
        }
        return annotated;
    }

    private List<RetrievalChunk> rrfFuse(List<List<RetrievalChunk>> lists, int topK) {
        Map<String, Double> rrfScores = new HashMap<>();
        Map<String, RetrievalChunk> docMap = new HashMap<>();
        Map<String, Integer> vectorRanks = new HashMap<>();
        Map<String, Integer> bm25Ranks = new HashMap<>();

        for (int listIndex = 0; listIndex < lists.size(); listIndex++) {
            List<RetrievalChunk> list = lists.get(listIndex);
            for (int rank = 0; rank < list.size(); rank++) {
                RetrievalChunk doc = list.get(rank);
                String docId = doc.getId();
                docMap.putIfAbsent(docId, doc);
                rrfScores.merge(docId, 1.0 / (RRF_K + rank + 1), Double::sum);
                if (listIndex == 0) {
                    vectorRanks.putIfAbsent(docId, rank + 1);
                } else if (listIndex == 1) {
                    bm25Ranks.putIfAbsent(docId, rank + 1);
                }
            }
        }

        return rrfScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(entry -> {
                    RetrievalChunk doc = docMap.get(entry.getKey());
                    Map<String, Object> metadata = new LinkedHashMap<>();
                    if (doc.getMetadata() != null) {
                        metadata.putAll(doc.getMetadata());
                    }
                    Integer vectorRank = vectorRanks.get(entry.getKey());
                    Integer bm25Rank = bm25Ranks.get(entry.getKey());
                    metadata.put("retrievalSource", retrievalSource(vectorRank, bm25Rank));
                    if (vectorRank != null) {
                        metadata.put("vectorRank", vectorRank);
                    }
                    if (bm25Rank != null) {
                        metadata.put("bm25Rank", bm25Rank);
                    }
                    metadata.put("rrfScore", entry.getValue());

                    return RetrievalChunk.builder()
                            .id(doc.getId())
                            .content(doc.getContent())
                            .score(entry.getValue())
                            .metadata(metadata)
                            .build();
                })
                .collect(Collectors.toList());
    }

    private String retrievalSource(Integer vectorRank, Integer bm25Rank) {
        if (vectorRank != null && bm25Rank != null) {
            return "both";
        }
        if (vectorRank != null) {
            return "vector_only";
        }
        if (bm25Rank != null) {
            return "bm25_only";
        }
        return "unknown";
    }

    private static final class Bm25IndexHolder {
        private final Bm25Search index;
        private final long builtAtMillis;

        private Bm25IndexHolder(Bm25Search index, long builtAtMillis) {
            this.index = index;
            this.builtAtMillis = builtAtMillis;
        }

        private boolean isStale() {
            return System.currentTimeMillis() - builtAtMillis > CORPUS_INDEX_TTL_MS;
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SearchOptions {
        private int topK;
        private double similarityThreshold;
        private boolean hybridSearch;
        private boolean cacheEnabled;
    }
}
