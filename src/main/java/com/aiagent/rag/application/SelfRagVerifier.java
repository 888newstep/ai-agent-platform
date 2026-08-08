package com.aiagent.rag.application;

import com.aiagent.infrastructure.config.AiProperties;
import com.aiagent.knowledge.domain.RetrievalChunk;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Heuristic self-verifier that checks whether retrieved chunks cover the
 * question keywords strongly enough to accept the current retrieval round.
 */
@Component
public class SelfRagVerifier {

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
            return AdaptiveRagVerificationResult.builder()
                    .level(RagVerificationLevel.NONE)
                    .score(0.0)
                    .matchedKeywords(List.of())
                    .missingKeywords(List.of())
                    .relevantChunkCount(0)
                    .reason("no verification keywords")
                    .build();
        }

        Set<String> matchedKeywords = new LinkedHashSet<>();
        int relevantChunkCount = 0;
        double accumulatedScore = 0.0;

        if (chunks != null) {
            for (RetrievalChunk chunk : chunks) {
                String content = chunk.getContent();
                if (content == null) {
                    continue;
                }

                List<String> chunkMatches = new ArrayList<>();
                for (String keyword : keywords) {
                    if (content.contains(keyword)) {
                        matchedKeywords.add(keyword);
                        chunkMatches.add(keyword);
                    }
                }

                if (!chunkMatches.isEmpty()) {
                    relevantChunkCount++;
                    double chunkScore = (double) chunkMatches.size() / keywords.size();
                    accumulatedScore += chunkScore + Math.min(0.1, Math.max(chunk.getScore(), 0.0) * 0.05);
                }
            }
        }

        double baseScore = matchedKeywords.isEmpty() ? 0.0 : (double) matchedKeywords.size() / keywords.size();
        double score = Math.max(baseScore, Math.min(1.0, accumulatedScore));
        double threshold = routeType == RagRouteType.MULTI_HOP
                ? aiProperties.getRag().getAdaptive().getMultiHopThreshold()
                : aiProperties.getRag().getAdaptive().getVerificationThreshold();

        RagVerificationLevel level;
        if (matchedKeywords.isEmpty()) {
            level = RagVerificationLevel.NONE;
        } else if (score >= threshold && (routeType != RagRouteType.MULTI_HOP || relevantChunkCount >= 2 || score >= threshold + 0.08)) {
            level = RagVerificationLevel.HIGH;
        } else if (score >= threshold / 2.0) {
            level = RagVerificationLevel.MEDIUM;
        } else {
            level = RagVerificationLevel.LOW;
        }

        List<String> matched = new ArrayList<>(matchedKeywords);
        List<String> missing = AdaptiveRagTextSupport.missingKeywords(keywords, String.join(" ", matched));
        String reason = "matched=" + matched.size() + ", missing=" + missing.size() + ", chunks=" + relevantChunkCount;

        return AdaptiveRagVerificationResult.builder()
                .level(level)
                .score(score)
                .matchedKeywords(matched)
                .missingKeywords(missing)
                .relevantChunkCount(relevantChunkCount)
                .reason(reason)
                .build();
    }
}
