package com.aiagent.rag.application;

import com.aiagent.infrastructure.cache.RagCacheService;
import com.aiagent.infrastructure.config.AiProperties;
import com.aiagent.infrastructure.metrics.PlatformMetricsService;
import com.aiagent.knowledge.application.DocumentService;
import com.aiagent.knowledge.domain.RetrievalChunk;
import com.aiagent.knowledge.infrastructure.vectorstore.VectorStoreService;
import com.aiagent.shared.exception.KnowledgeRetrievalUnavailableException;
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
                aiProperties.getRag().getHybridRrfK(),
                aiProperties.getRag().getHybridVectorCandidateTopK(),
                aiProperties.getRag().getHybridBm25CandidateTopK(),
                aiProperties.getRag().getHybridBm25CorpusMaxDocs());
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
                AiProperties.Rag ragConfig = aiProperties.getRag();
                int vectorCandidateTopK = positiveOrDefault(ragConfig.getHybridVectorCandidateTopK(), 20);
                int bm25CandidateTopK = positiveOrDefault(ragConfig.getHybridBm25CandidateTopK(), 20);
                List<RetrievalChunk> bm25Results = ragConfig.isHybridCorpusBm25Enabled()
                        ? bm25SearchCorpus(
                                query,
                                bm25CandidateTopK,
                                ragConfig.isBm25StopwordEnabled(),
                                positiveOrDefault(ragConfig.getHybridBm25CorpusMaxDocs(), 5000))
                        : List.of();
                int vectorSearchTopK = bm25Results.isEmpty()
                        ? Math.max(50, Math.max(vectorCandidateTopK, bm25CandidateTopK))
                        : vectorCandidateTopK;
                List<RetrievalChunk> vectorResults = vectorSearch(
                        query, vectorSearchTopK, options.getSimilarityThreshold());
                log.debug("Vector result size={}", vectorResults.size());

                if (bm25Results.isEmpty()) {
                    log.debug("Corpus BM25 disabled or unavailable, using candidate-pool BM25");
                    bm25Results = bm25SearchOnCandidates(
                            query, vectorResults, bm25CandidateTopK,
                            ragConfig.isBm25StopwordEnabled());
                }
                log.debug("BM25 result size={}", bm25Results.size());

                finalResults = rrfFuse(
                        List.of(vectorResults, bm25Results),
                        options.getTopK(),
                        ragConfig.getHybridVectorWeight(),
                        ragConfig.getHybridBm25Weight(),
                        ragConfig.getHybridRrfK());
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
                + "|hybrid=" + options.isHybridSearch()
                + (options.isHybridSearch()
                ? String.format(Locale.ROOT,
                        "|vw=%.4f|bw=%.4f|rrfK=%d|stopwords=%s|vTopK=%d|bTopK=%d|bMaxDocs=%d|corpusBm25=%s",
                        aiProperties.getRag().getHybridVectorWeight(),
                        aiProperties.getRag().getHybridBm25Weight(),
                        aiProperties.getRag().getHybridRrfK(),
                        aiProperties.getRag().isBm25StopwordEnabled(),
                        aiProperties.getRag().getHybridVectorCandidateTopK(),
                        aiProperties.getRag().getHybridBm25CandidateTopK(),
                        aiProperties.getRag().getHybridBm25CorpusMaxDocs(),
                        aiProperties.getRag().isHybridCorpusBm25Enabled())
                : "");
    }

    private List<RetrievalChunk> vectorSearch(String query, int topK, double threshold) {
        try {
            return documentService.searchSimilar(query, topK, threshold);
        } catch (KnowledgeRetrievalUnavailableException exception) {
            throw exception;
        } catch (Exception e) {
            log.error("Vector search failed", e);
            throw new KnowledgeRetrievalUnavailableException(
                    "Knowledge retrieval is temporarily unavailable", e);
        }
    }

    private List<RetrievalChunk> bm25SearchOnCandidates(String query,
                                                        List<RetrievalChunk> candidates,
                                                        int topK,
                                                        boolean stopwordEnabled) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        try {
            Bm25Search bm25 = new Bm25Search(candidates, stopwordEnabled);
            return bm25.search(query, topK);
        } catch (Exception e) {
            log.warn("BM25 search failed: {}", e.getMessage());
            return List.of();
        }
    }

    private List<RetrievalChunk> bm25SearchCorpus(String query,
                                                   int topK,
                                                   boolean stopwordEnabled,
                                                   int maxDocs) {
        Bm25Search index = corpusBm25Index(stopwordEnabled, maxDocs);
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

    private Bm25Search corpusBm25Index(boolean stopwordEnabled, int maxDocs) {
        Bm25IndexHolder holder = corpusBm25;
        if (holder != null && !holder.isStale(stopwordEnabled, maxDocs)) {
            return holder.index;
        }
        synchronized (indexLock) {
            holder = corpusBm25;
            if (holder != null && !holder.isStale(stopwordEnabled, maxDocs)) {
                return holder.index;
            }
            List<RetrievalChunk> corpus;
            try {
                corpus = vectorStoreService.fetchAllChunks(maxDocs);
            } catch (Exception e) {
                log.warn("Failed to load corpus for BM25 index: {}", e.getMessage());
                corpusBm25 = Bm25IndexHolder.unavailable(stopwordEnabled, maxDocs);
                return null;
            }
            if (corpus == null || corpus.isEmpty()) {
                log.warn("Corpus is empty; corpus BM25 unavailable");
                corpusBm25 = Bm25IndexHolder.unavailable(stopwordEnabled, maxDocs);
                return null;
            }
            if (corpus.size() >= maxDocs) {
                log.warn("BM25 corpus reached configured limit {}; refusing to treat a truncated corpus as complete",
                        maxDocs);
                corpusBm25 = Bm25IndexHolder.unavailable(stopwordEnabled, maxDocs);
                return null;
            }
            Bm25Search built = new Bm25Search(corpus, stopwordEnabled);
            corpusBm25 = new Bm25IndexHolder(built, System.currentTimeMillis(), stopwordEnabled, maxDocs);
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

    private List<RetrievalChunk> rrfFuse(List<List<RetrievalChunk>> lists,
                                         int topK,
                                         double vectorWeight,
                                         double bm25Weight,
                                         int rrfK) {
        double safeVectorWeight = positiveOrDefault(vectorWeight, 0.95);
        double safeBm25Weight = positiveOrDefault(bm25Weight, 0.05);
        int safeRrfK = positiveOrDefault(rrfK, 60);
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
                double routeWeight = listIndex == 0 ? safeVectorWeight : safeBm25Weight;
                rrfScores.merge(docId,
                        routeWeight / (safeRrfK + rank + 1),
                        (a, b) -> a + b);
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
                    metadata.put("vectorWeight", safeVectorWeight);
                    metadata.put("bm25Weight", safeBm25Weight);
                    metadata.put("rrfK", safeRrfK);

                    return RetrievalChunk.builder()
                            .id(doc.getId())
                            .content(doc.getContent())
                            .score(entry.getValue())
                            .metadata(metadata)
                            .build();
                })
                .collect(Collectors.toList());
    }

    private int positiveOrDefault(int value, int fallback) {
        return value > 0 ? value : fallback;
    }

    private double positiveOrDefault(double value, double fallback) {
        return Double.isFinite(value) && value > 0 ? value : fallback;
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
        private final boolean stopwordEnabled;
        private final int maxDocs;

        private Bm25IndexHolder(Bm25Search index,
                                long builtAtMillis,
                                boolean stopwordEnabled,
                                int maxDocs) {
            this.index = index;
            this.builtAtMillis = builtAtMillis;
            this.stopwordEnabled = stopwordEnabled;
            this.maxDocs = maxDocs;
        }

        private static Bm25IndexHolder unavailable(boolean stopwordEnabled, int maxDocs) {
            return new Bm25IndexHolder(null, System.currentTimeMillis(), stopwordEnabled, maxDocs);
        }

        private boolean isStale(boolean requestedStopwordEnabled, int requestedMaxDocs) {
            return stopwordEnabled != requestedStopwordEnabled
                    || maxDocs != requestedMaxDocs
                    || System.currentTimeMillis() - builtAtMillis > CORPUS_INDEX_TTL_MS;
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
