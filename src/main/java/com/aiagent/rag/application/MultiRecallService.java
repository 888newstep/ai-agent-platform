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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

@Slf4j
@Service
public class MultiRecallService {

    private static final long CORPUS_INDEX_TTL_MS = 5 * 60 * 1000L;

    private final DocumentService documentService;
    private final AiProperties aiProperties;
    private final RagCacheService ragCacheService;
    private final PlatformMetricsService metricsService;
    private final VectorStoreService vectorStoreService;
    private final EcommerceQaLexicalSearchService lexicalSearchService;
    private final CrossEncoderRerankClient crossEncoderRerankClient;
    private final Executor ioTaskExecutor;
    private final Object indexLock = new Object();
    private volatile Bm25IndexHolder corpusBm25;

    public MultiRecallService(DocumentService documentService,
                              AiProperties aiProperties,
                              RagCacheService ragCacheService,
                              PlatformMetricsService metricsService,
                              VectorStoreService vectorStoreService,
                              EcommerceQaLexicalSearchService lexicalSearchService,
                              CrossEncoderRerankClient crossEncoderRerankClient,
                              @Qualifier("ioTaskExecutor") Executor ioTaskExecutor) {
        this.documentService = documentService;
        this.aiProperties = aiProperties;
        this.ragCacheService = ragCacheService;
        this.metricsService = metricsService;
        this.vectorStoreService = vectorStoreService;
        this.lexicalSearchService = lexicalSearchService;
        this.crossEncoderRerankClient = crossEncoderRerankClient;
        this.ioTaskExecutor = ioTaskExecutor;
    }

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
                int mysqlCandidateTopK = positiveOrDefault(ragConfig.getHybridMysqlCandidateTopK(), 50);
                CompletableFuture<List<RetrievalChunk>> lexicalFuture = startLexicalSearch(
                        query, mysqlCandidateTopK, ragConfig.isHybridMysqlFulltextEnabled());
                int fallbackVectorTopK = Math.max(50, Math.max(vectorCandidateTopK, bm25CandidateTopK));
                int vectorSearchTopK = ragConfig.isHybridMysqlFulltextEnabled()
                        ? options.getTopK()
                        : fallbackVectorTopK;
                List<RetrievalChunk> stableVectorResults;
                List<RetrievalChunk> vectorResults;
                if (ragConfig.isHybridCrossEncoderEnabled() && vectorCandidateTopK > vectorSearchTopK) {
                    Map<Integer, List<RetrievalChunk>> vectorBatches = vectorSearch(
                            query,
                            List.of(vectorSearchTopK, vectorCandidateTopK),
                            options.getSimilarityThreshold());
                    stableVectorResults = vectorBatches.getOrDefault(vectorSearchTopK, List.of());
                    vectorResults = mergeCandidates(
                            stableVectorResults,
                            vectorBatches.getOrDefault(vectorCandidateTopK, List.of()));
                } else {
                    stableVectorResults = vectorSearch(
                            query, vectorSearchTopK, options.getSimilarityThreshold());
                    vectorResults = stableVectorResults;
                }
                log.debug("Vector result size={}, stable vector size={}",
                        vectorResults.size(), stableVectorResults.size());

                List<RetrievalChunk> lexicalResults = awaitLexicalResults(lexicalFuture);
                if (lexicalResults.isEmpty() && ragConfig.isHybridCorpusBm25Enabled()) {
                    lexicalResults = bm25SearchCorpus(
                            query,
                            bm25CandidateTopK,
                            ragConfig.isBm25StopwordEnabled(),
                            positiveOrDefault(ragConfig.getHybridBm25CorpusMaxDocs(), 5000));
                }
                if (lexicalResults.isEmpty()) {
                    log.debug("Corpus BM25 disabled or unavailable, using candidate-pool BM25");
                    if (vectorSearchTopK < fallbackVectorTopK) {
                        vectorResults = vectorSearch(
                                query, fallbackVectorTopK, options.getSimilarityThreshold());
                    }
                    lexicalResults = bm25SearchOnCandidates(
                            query, vectorResults, bm25CandidateTopK,
                            ragConfig.isBm25StopwordEnabled());
                }
                log.debug("Lexical result size={}", lexicalResults.size());

                List<RetrievalChunk> rrfResults = rrfFuse(
                        List.of(vectorResults, lexicalResults),
                        Math.max(options.getTopK(), ragConfig.getHybridRerankCandidateTopK()),
                        ragConfig.getHybridVectorWeight(),
                        ragConfig.getHybridBm25Weight(),
                        ragConfig.getHybridRrfK(),
                        ragConfig.getHybridPreserveVectorTopK());
                finalResults = ragConfig.isHybridCrossEncoderEnabled()
                        ? crossEncoderRerank(
                                query,
                                stableVectorResults,
                                vectorResults,
                                lexicalResults,
                                rrfResults,
                                options.getTopK(),
                                ragConfig.getHybridPreserveVectorTopK(),
                                ragConfig.getHybridRerankCandidateTopK(),
                                ragConfig.isHybridRerankFailOpen())
                        : limit(rrfResults, options.getTopK());
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
        String baseKey = query
                + "|topK=" + options.getTopK()
                + "|threshold=" + String.format(Locale.ROOT, "%.4f", options.getSimilarityThreshold())
                + "|hybrid=" + options.isHybridSearch();
        if (!options.isHybridSearch()) {
            return baseKey;
        }
        String hybridKey = baseKey + String.format(Locale.ROOT,
                        "|vw=%.4f|bw=%.4f|rrfK=%d|stopwords=%s|vTopK=%d|bTopK=%d|bMaxDocs=%d|corpusBm25=%s|mysqlFt=%s|mysqlTopK=%d|preserveV=%d",
                        aiProperties.getRag().getHybridVectorWeight(),
                        aiProperties.getRag().getHybridBm25Weight(),
                        aiProperties.getRag().getHybridRrfK(),
                        aiProperties.getRag().isBm25StopwordEnabled(),
                        aiProperties.getRag().getHybridVectorCandidateTopK(),
                        aiProperties.getRag().getHybridBm25CandidateTopK(),
                        aiProperties.getRag().getHybridBm25CorpusMaxDocs(),
                        aiProperties.getRag().isHybridCorpusBm25Enabled(),
                        aiProperties.getRag().isHybridMysqlFulltextEnabled(),
                        aiProperties.getRag().getHybridMysqlCandidateTopK(),
                        aiProperties.getRag().getHybridPreserveVectorTopK());
        if (!aiProperties.getRag().isHybridCrossEncoderEnabled()) {
            return hybridKey;
        }
        return hybridKey + String.format(Locale.ROOT,
                        "|cross=true|rerankTopK=%d|rerankFailOpen=%s",
                        aiProperties.getRag().getHybridRerankCandidateTopK(),
                        aiProperties.getRag().isHybridRerankFailOpen());
    }

    private List<RetrievalChunk> crossEncoderRerank(String query,
                                                    List<RetrievalChunk> stableVectorResults,
                                                    List<RetrievalChunk> vectorResults,
                                                    List<RetrievalChunk> lexicalResults,
                                                    List<RetrievalChunk> fallbackResults,
                                                    int topK,
                                                    int preserveVectorTopK,
                                                    int candidateTopK,
                                                    boolean failOpen) {
        Map<String, RetrievalChunk> candidates = new LinkedHashMap<>();
        vectorResults.forEach(chunk -> candidates.putIfAbsent(chunk.getId(), chunk));
        lexicalResults.forEach(chunk -> candidates.putIfAbsent(chunk.getId(), chunk));
        Map<String, RetrievalChunk> fallbackById = fallbackResults.stream()
                .collect(Collectors.toMap(RetrievalChunk::getId, chunk -> chunk, (left, right) -> left));
        List<RetrievalChunk> boundedCandidates = candidates.values().stream()
                .map(chunk -> fallbackById.getOrDefault(chunk.getId(), chunk))
                .limit(positiveOrDefault(candidateTopK, 25))
                .toList();
        if (boundedCandidates.isEmpty()) {
            return List.of();
        }

        try {
            List<CrossEncoderRerankClient.RerankScore> scores = crossEncoderRerankClient.rerank(
                    query,
                    boundedCandidates.stream().map(RetrievalChunk::getContent).toList());
            if (scores == null || scores.size() != boundedCandidates.size()) {
                throw new IllegalStateException("Cross-encoder did not score every hybrid candidate");
            }
            Map<Integer, Double> scoreByIndex = new HashMap<>();
            for (CrossEncoderRerankClient.RerankScore score : scores) {
                if (score == null || score.index() < 0 || score.index() >= boundedCandidates.size()
                        || !Double.isFinite(score.score()) || score.score() < 0.0 || score.score() > 1.0
                        || scoreByIndex.putIfAbsent(score.index(), score.score()) != null) {
                    throw new IllegalStateException("Cross-encoder returned invalid hybrid candidate scores");
                }
            }
            if (scoreByIndex.size() != boundedCandidates.size()) {
                throw new IllegalStateException("Cross-encoder omitted hybrid candidate scores");
            }

            List<RetrievalChunk> ranked = java.util.stream.IntStream.range(0, boundedCandidates.size())
                    .mapToObj(index -> withRerankMetadata(boundedCandidates.get(index), scoreByIndex.get(index), index + 1))
                    .sorted(java.util.Comparator.comparingDouble(RetrievalChunk::getScore).reversed()
                            .thenComparing(RetrievalChunk::getId))
                    .toList();
            return preserveVectorResults(ranked, stableVectorResults, topK, preserveVectorTopK);
        } catch (RuntimeException exception) {
            if (!failOpen) {
                throw exception;
            }
            log.warn("Hybrid cross-encoder reranking failed; using RRF fallback: {}", exception.getMessage());
            return limit(fallbackResults, topK);
        }
    }

    private RetrievalChunk withRerankMetadata(RetrievalChunk chunk, double score, int originalRank) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (chunk.getMetadata() != null) {
            metadata.putAll(chunk.getMetadata());
        }
        metadata.put("hybridRerankProvider", "cross-encoder");
        metadata.put("hybridRerankScore", score);
        metadata.put("hybridPreRerankRank", originalRank);
        return RetrievalChunk.builder()
                .id(chunk.getId())
                .content(chunk.getContent())
                .score(score)
                .metadata(metadata)
                .build();
    }

    private List<RetrievalChunk> preserveVectorResults(List<RetrievalChunk> ranked,
                                                       List<RetrievalChunk> vectorResults,
                                                       int topK,
                                                       int preserveVectorTopK) {
        int preserveCount = Math.max(0, Math.min(preserveVectorTopK, topK));
        Map<String, RetrievalChunk> rankedById = ranked.stream()
                .collect(Collectors.toMap(RetrievalChunk::getId, chunk -> chunk, (left, right) -> left));
        Map<String, RetrievalChunk> ordered = new LinkedHashMap<>();
        vectorResults.stream()
                .limit(preserveCount)
                .map(RetrievalChunk::getId)
                .map(rankedById::get)
                .filter(java.util.Objects::nonNull)
                .forEach(chunk -> ordered.put(chunk.getId(), chunk));
        ranked.forEach(chunk -> ordered.putIfAbsent(chunk.getId(), chunk));
        return ordered.values().stream().limit(topK).toList();
    }

    private List<RetrievalChunk> limit(List<RetrievalChunk> chunks, int topK) {
        return chunks.size() > topK ? chunks.subList(0, topK) : chunks;
    }

    private List<RetrievalChunk> mergeCandidates(List<RetrievalChunk> primary,
                                                 List<RetrievalChunk> secondary) {
        Map<String, RetrievalChunk> merged = new LinkedHashMap<>();
        primary.forEach(chunk -> merged.putIfAbsent(chunk.getId(), chunk));
        secondary.forEach(chunk -> merged.putIfAbsent(chunk.getId(), chunk));
        return List.copyOf(merged.values());
    }

    private CompletableFuture<List<RetrievalChunk>> startLexicalSearch(String query,
                                                                        int topK,
                                                                        boolean enabled) {
        if (!enabled) {
            return CompletableFuture.completedFuture(List.of());
        }
        try {
            return CompletableFuture.supplyAsync(() -> lexicalSearchService.search(query, topK), ioTaskExecutor);
        } catch (RuntimeException exception) {
            log.warn("Unable to schedule lexical retrieval: {}", exception.getMessage());
            return CompletableFuture.completedFuture(List.of());
        }
    }

    private List<RetrievalChunk> awaitLexicalResults(CompletableFuture<List<RetrievalChunk>> future) {
        try {
            List<RetrievalChunk> results = future.join();
            return results == null ? List.of() : results;
        } catch (CompletionException exception) {
            log.warn("Lexical retrieval failed asynchronously: {}", exception.getMessage());
            return List.of();
        }
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

    private Map<Integer, List<RetrievalChunk>> vectorSearch(String query,
                                                            List<Integer> topKs,
                                                            double threshold) {
        try {
            return documentService.searchSimilar(query, topKs, threshold);
        } catch (Exception exception) {
            log.error("Vector search failed", exception);
            throw new KnowledgeRetrievalUnavailableException(
                    "Knowledge retrieval is temporarily unavailable", exception);
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
                                         int rrfK,
                                         int preserveVectorTopK) {
        double safeVectorWeight = positiveOrDefault(vectorWeight, 0.95);
        double safeBm25Weight = positiveOrDefault(bm25Weight, 0.05);
        int safeRrfK = positiveOrDefault(rrfK, 60);
        Map<String, Double> rrfScores = new HashMap<>();
        Map<String, RetrievalChunk> docMap = new HashMap<>();
        Map<String, Integer> vectorRanks = new HashMap<>();
        Map<String, Integer> lexicalRanks = new HashMap<>();

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
                    lexicalRanks.putIfAbsent(docId, rank + 1);
                }
            }
        }

        List<RetrievalChunk> ranked = rrfScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed()
                        .thenComparing(Map.Entry::getKey))
                .map(entry -> {
                    RetrievalChunk doc = docMap.get(entry.getKey());
                    Map<String, Object> metadata = new LinkedHashMap<>();
                    if (doc.getMetadata() != null) {
                        metadata.putAll(doc.getMetadata());
                    }
                    Integer vectorRank = vectorRanks.get(entry.getKey());
                    Integer lexicalRank = lexicalRanks.get(entry.getKey());
                    metadata.put("retrievalSource", retrievalSource(vectorRank, lexicalRank));
                    if (vectorRank != null) {
                        metadata.put("vectorRank", vectorRank);
                    }
                    if (lexicalRank != null) {
                        metadata.put("lexicalRank", lexicalRank);
                        metadata.put("bm25Rank", lexicalRank);
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

        int preserveCount = Math.max(0, Math.min(preserveVectorTopK, topK));
        if (preserveCount == 0 || lists.isEmpty()) {
            return ranked.size() > topK ? ranked.subList(0, topK) : ranked;
        }

        Map<String, RetrievalChunk> rankedById = ranked.stream()
                .collect(Collectors.toMap(RetrievalChunk::getId, chunk -> chunk, (left, right) -> left));
        Map<String, RetrievalChunk> ordered = new LinkedHashMap<>();
        lists.get(0).stream()
                .limit(preserveCount)
                .map(RetrievalChunk::getId)
                .map(rankedById::get)
                .filter(java.util.Objects::nonNull)
                .forEach(chunk -> ordered.put(chunk.getId(), chunk));
        ranked.forEach(chunk -> ordered.putIfAbsent(chunk.getId(), chunk));
        return ordered.values().stream().limit(topK).toList();
    }

    private int positiveOrDefault(int value, int fallback) {
        return value > 0 ? value : fallback;
    }

    private double positiveOrDefault(double value, double fallback) {
        return Double.isFinite(value) && value > 0 ? value : fallback;
    }

    private String retrievalSource(Integer vectorRank, Integer lexicalRank) {
        if (vectorRank != null && lexicalRank != null) {
            return "both";
        }
        if (vectorRank != null) {
            return "vector_only";
        }
        if (lexicalRank != null) {
            return "lexical_only";
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
