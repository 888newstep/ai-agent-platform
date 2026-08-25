package com.aiagent.rag.application;

import com.aiagent.infrastructure.config.AiProperties;
import com.aiagent.knowledge.domain.RetrievalChunk;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Heuristic self-verifier that checks whether retrieved chunks cover the
 * question keywords strongly enough to accept the current retrieval round.
 */
@Component
public class SelfRagVerifier {

    private static final Pattern NUMBER_PATTERN = Pattern.compile("\\d+(?:\\.\\d+)?%?");

    private final AiProperties aiProperties;

    public SelfRagVerifier(AiProperties aiProperties) {
        this.aiProperties = aiProperties;
    }

    public AdaptiveRagVerificationResult verify(String originalQuestion,
                                                AdaptiveRagRewriteResult rewriteResult,
                                                List<RetrievalChunk> chunks,
                                                RagRouteType routeType) {
        List<String> keywords = rewriteResult == null ? List.of() : rewriteResult.getKeywords();
        if (keywords == null || keywords.isEmpty()) {
            keywords = AdaptiveRagTextSupport.extractKeywords(originalQuestion, aiProperties.getRag().getAdaptive().getMinKeywordCount() + 6);
        }

        if (keywords.isEmpty()) {
            return emptyResult("no verification keywords");
        }

        if (chunks == null || chunks.isEmpty()) {
            return emptyResult("no reranked evidence");
        }

        Set<String> matchedKeywords = new LinkedHashSet<>();
        int relevantChunkCount = 0;
        double maxSemanticScore = 0.0;
        double maxChunkCoverage = 0.0;
        Set<String> requiredNumbers = extractNumbers(originalQuestion);
        Set<String> supportedNumbers = new LinkedHashSet<>();
        AiProperties.Adaptive config = aiProperties.getRag().getAdaptive();

        for (RetrievalChunk chunk : chunks) {
            String content = chunk.getContent();
            if (content == null) {
                continue;
            }
            double semanticScore = semanticScore(chunk, config);
            double chunkCoverage = weightedCoverage(keywords, content);
            maxSemanticScore = Math.max(maxSemanticScore, semanticScore);
            maxChunkCoverage = Math.max(maxChunkCoverage, chunkCoverage);

            if (semanticScore < semanticThreshold(chunk, config)
                    || chunkCoverage < config.getMinimumKeywordCoverage()) {
                continue;
            }
            relevantChunkCount++;
            matchedKeywords.addAll(AdaptiveRagTextSupport.matchedKeywords(keywords, content));
            supportedNumbers.addAll(extractNumbers(content));
        }

        double keywordCoverage = weightedCoverage(keywords, String.join(" ", matchedKeywords));
        boolean numbersSupported = supportedNumbers.containsAll(requiredNumbers);
        boolean evidenceSufficient = relevantChunkCount > 0 && numbersSupported;
        double score = evidenceSufficient
                ? clamp(maxSemanticScore * 0.65 + keywordCoverage * 0.35)
                : 0.0;
        double threshold = routeType == RagRouteType.MULTI_HOP
                ? config.getMultiHopThreshold()
                : config.getVerificationThreshold();

        RagVerificationLevel level;
        if (!evidenceSufficient) {
            level = RagVerificationLevel.NONE;
        } else if (score >= threshold
                && (routeType != RagRouteType.MULTI_HOP || relevantChunkCount >= 2 || score >= threshold + 0.08)) {
            level = RagVerificationLevel.HIGH;
        } else if (score >= Math.max(0.0, threshold - 0.10)) {
            level = RagVerificationLevel.MEDIUM;
        } else {
            level = RagVerificationLevel.LOW;
        }

        List<String> matched = new ArrayList<>(matchedKeywords);
        List<String> missing = AdaptiveRagTextSupport.missingKeywords(keywords, String.join(" ", matched));
        String reason = "semantic=" + format(maxSemanticScore)
                + ", coverage=" + format(keywordCoverage)
                + ", maxChunkCoverage=" + format(maxChunkCoverage)
                + ", matched=" + matched.size()
                + ", missing=" + missing.size()
                + ", chunks=" + relevantChunkCount
                + ", numbersSupported=" + numbersSupported;

        return AdaptiveRagVerificationResult.builder()
                .level(level)
                .score(score)
                .semanticScore(maxSemanticScore)
                .keywordCoverage(keywordCoverage)
                .evidenceSufficient(evidenceSufficient)
                .matchedKeywords(matched)
                .missingKeywords(missing)
                .relevantChunkCount(relevantChunkCount)
                .reason(reason)
                .build();
    }

    private AdaptiveRagVerificationResult emptyResult(String reason) {
        return AdaptiveRagVerificationResult.builder()
                .level(RagVerificationLevel.NONE)
                .score(0.0)
                .semanticScore(0.0)
                .keywordCoverage(0.0)
                .evidenceSufficient(false)
                .matchedKeywords(List.of())
                .missingKeywords(List.of())
                .relevantChunkCount(0)
                .reason(reason)
                .build();
    }

    private double semanticScore(RetrievalChunk chunk, AiProperties.Adaptive config) {
        Map<String, Object> metadata = chunk.getMetadata();
        if (metadata != null
                && metadata.get(SemanticEvidenceReranker.SEMANTIC_SCORE_METADATA) instanceof Number value) {
            return clamp(value.doubleValue());
        }
        return config.isSemanticRerankEnabled() ? 0.0 : clamp(chunk.getScore());
    }

    private double semanticThreshold(RetrievalChunk chunk, AiProperties.Adaptive config) {
        Map<String, Object> metadata = chunk.getMetadata();
        if (metadata != null
                && metadata.get(SemanticEvidenceReranker.RERANK_MIN_SCORE_METADATA) instanceof Number value) {
            return clamp(value.doubleValue());
        }
        return config.getSemanticRerankMinScore();
    }

    private double weightedCoverage(List<String> keywords, String text) {
        if (keywords == null || keywords.isEmpty() || text == null || text.isBlank()) {
            return 0.0;
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        double totalWeight = 0.0;
        double matchedWeight = 0.0;
        for (String keyword : new LinkedHashSet<>(keywords)) {
            if (keyword == null || keyword.isBlank()) {
                continue;
            }
            int length = keyword.codePointCount(0, keyword.length());
            double weight = Math.max(1.0, Math.min(4.0, Math.sqrt(length)));
            totalWeight += weight;
            if (normalized.contains(keyword.toLowerCase(Locale.ROOT))) {
                matchedWeight += weight;
            }
        }
        return totalWeight == 0.0 ? 0.0 : clamp(matchedWeight / totalWeight);
    }

    private Set<String> extractNumbers(String text) {
        Set<String> numbers = new LinkedHashSet<>();
        if (text == null) {
            return numbers;
        }
        Matcher matcher = NUMBER_PATTERN.matcher(text);
        while (matcher.find()) {
            numbers.add(matcher.group());
        }
        return numbers;
    }

    private double clamp(double value) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    private String format(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }
}
