package com.aiagent.rag.application;

import com.aiagent.infrastructure.config.AiProperties;
import com.aiagent.knowledge.domain.RetrievalChunk;
import com.aiagent.shared.exception.KnowledgeRetrievalUnavailableException;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.CosineSimilarity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@Slf4j
public class SemanticEvidenceReranker implements EvidenceReranker {

    public static final String SEMANTIC_SCORE_METADATA = "semanticRerankScore";
    public static final String ORIGINAL_RANK_METADATA = "preRerankRank";
    public static final String RERANK_PROVIDER_METADATA = "rerankProvider";
    public static final String RERANK_MIN_SCORE_METADATA = "rerankMinScore";

    private final EmbeddingModel embeddingModel;
    private final AiProperties aiProperties;
    private final CrossEncoderRerankClient crossEncoderRerankClient;

    public SemanticEvidenceReranker(EmbeddingModel embeddingModel,
                                    AiProperties aiProperties,
                                    CrossEncoderRerankClient crossEncoderRerankClient) {
        this.embeddingModel = embeddingModel;
        this.aiProperties = aiProperties;
        this.crossEncoderRerankClient = crossEncoderRerankClient;
    }

    @Override
    public List<RetrievalChunk> rerank(String query, List<RetrievalChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        AiProperties.Adaptive config = aiProperties.getRag().getAdaptive();
        if (!config.isSemanticRerankEnabled()) {
            return List.copyOf(chunks);
        }
        if (!StringUtils.hasText(query)) {
            return List.of();
        }

        ScoringResult scoringResult = score(query, chunks, config);
        List<ScoredChunk> scored = new ArrayList<>(chunks.size());
        for (int index = 0; index < chunks.size(); index++) {
            RetrievalChunk chunk = chunks.get(index);
            double semanticScore = scoringResult.scores().get(index);
            if (semanticScore < scoringResult.minScore()) {
                continue;
            }
            Map<String, Object> metadata = new LinkedHashMap<>();
            if (chunk.getMetadata() != null) {
                metadata.putAll(chunk.getMetadata());
            }
            metadata.put(SEMANTIC_SCORE_METADATA, semanticScore);
            metadata.put(ORIGINAL_RANK_METADATA, index + 1);
            metadata.put(RERANK_PROVIDER_METADATA, scoringResult.provider());
            metadata.put(RERANK_MIN_SCORE_METADATA, scoringResult.minScore());
            scored.add(new ScoredChunk(
                    RetrievalChunk.builder()
                            .id(chunk.getId())
                            .content(chunk.getContent())
                            .score(chunk.getScore())
                            .metadata(metadata)
                            .build(),
                    semanticScore,
                    index));
        }

        int topK = Math.max(1, config.getSemanticRerankTopK());
        return scored.stream()
                .sorted(Comparator.comparingDouble(ScoredChunk::score).reversed()
                        .thenComparingInt(ScoredChunk::originalIndex))
                .limit(topK)
                .map(ScoredChunk::chunk)
                .toList();
    }

    private ScoringResult score(String query,
                                List<RetrievalChunk> chunks,
                                AiProperties.Adaptive config) {
        String provider = StringUtils.hasText(config.getRerankProvider())
                ? config.getRerankProvider().trim().toLowerCase(java.util.Locale.ROOT)
                : "embedding";
        if ("embedding".equals(provider)) {
            validateThreshold(config.getSemanticRerankMinScore(), "embedding rerank threshold");
            return new ScoringResult(
                    scoreWithEmbeddings(query, chunks),
                    "embedding",
                    config.getSemanticRerankMinScore());
        }
        if (!"cross-encoder".equals(provider)) {
            throw new KnowledgeRetrievalUnavailableException(
                    "Unsupported RAG rerank provider: " + config.getRerankProvider());
        }

        try {
            AiProperties.CrossEncoder crossEncoder = config.getCrossEncoder();
            if (crossEncoder == null) {
                throw new KnowledgeRetrievalUnavailableException(
                        "Cross-encoder reranker configuration is missing");
            }
            validateThreshold(crossEncoder.getMinScore(), "cross-encoder rerank threshold");
            return new ScoringResult(
                    scoreWithCrossEncoder(query, chunks),
                    "cross-encoder",
                    crossEncoder.getMinScore());
        } catch (RuntimeException exception) {
            AiProperties.CrossEncoder crossEncoder = config.getCrossEncoder();
            if (crossEncoder == null || !crossEncoder.isFallbackToEmbedding()) {
                if (exception instanceof KnowledgeRetrievalUnavailableException unavailableException) {
                    throw unavailableException;
                }
                throw new KnowledgeRetrievalUnavailableException(
                        "Cross-encoder evidence reranking is temporarily unavailable", exception);
            }
            log.warn("Cross-encoder reranking failed; using explicitly configured embedding fallback: {}",
                    exception.getMessage());
            return new ScoringResult(
                    scoreWithEmbeddings(query, chunks),
                    "embedding-fallback",
                    config.getSemanticRerankMinScore());
        }
    }

    private List<Double> scoreWithEmbeddings(String query, List<RetrievalChunk> chunks) {
        List<TextSegment> inputs = new ArrayList<>(chunks.size() + 1);
        inputs.add(TextSegment.from(query));
        for (RetrievalChunk chunk : chunks) {
            inputs.add(TextSegment.from(chunk.getContent() == null ? "" : chunk.getContent()));
        }

        List<Embedding> embeddings;
        try {
            embeddings = embeddingModel.embedAll(inputs).content();
        } catch (RuntimeException exception) {
            throw new KnowledgeRetrievalUnavailableException(
                    "Evidence reranking is temporarily unavailable", exception);
        }
        if (embeddings == null || embeddings.size() != inputs.size()) {
            throw new KnowledgeRetrievalUnavailableException(
                    "Evidence reranking returned an invalid embedding response");
        }

        Embedding queryEmbedding = embeddings.get(0);
        List<Double> scores = new ArrayList<>(chunks.size());
        for (int index = 0; index < chunks.size(); index++) {
            scores.add(clamp(CosineSimilarity.between(queryEmbedding, embeddings.get(index + 1))));
        }
        return scores;
    }

    private List<Double> scoreWithCrossEncoder(String query, List<RetrievalChunk> chunks) {
        List<String> documents = chunks.stream()
                .map(chunk -> chunk.getContent() == null ? "" : chunk.getContent())
                .toList();
        List<CrossEncoderRerankClient.RerankScore> response = crossEncoderRerankClient.rerank(query, documents);
        if (response == null || response.size() != chunks.size()) {
            throw new KnowledgeRetrievalUnavailableException(
                    "Cross-encoder reranker did not score every evidence chunk");
        }

        Double[] scores = new Double[chunks.size()];
        for (CrossEncoderRerankClient.RerankScore item : response) {
            if (item == null || item.index() < 0 || item.index() >= chunks.size()
                    || scores[item.index()] != null || !Double.isFinite(item.score())
                    || item.score() < 0.0 || item.score() > 1.0) {
                throw new KnowledgeRetrievalUnavailableException(
                        "Cross-encoder reranker returned invalid indexes or scores");
            }
            scores[item.index()] = clamp(item.score());
        }
        if (java.util.Arrays.stream(scores).anyMatch(java.util.Objects::isNull)) {
            throw new KnowledgeRetrievalUnavailableException(
                    "Cross-encoder reranker omitted an evidence score");
        }
        return java.util.Arrays.asList(scores);
    }

    private double clamp(double value) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    private void validateThreshold(double threshold, String name) {
        if (!Double.isFinite(threshold) || threshold < 0.0 || threshold > 1.0) {
            throw new KnowledgeRetrievalUnavailableException(name + " must be between 0 and 1");
        }
    }

    private record ScoredChunk(RetrievalChunk chunk, double score, int originalIndex) {
    }

    private record ScoringResult(List<Double> scores, String provider, double minScore) {
    }
}
