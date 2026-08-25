package com.aiagent.rag.application;

import com.aiagent.infrastructure.config.AiProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Rewrites user questions into retrieval-friendly keyword queries.
 * It can also incorporate feedback from the previous verification round.
 */
@Component
public class QueryRewriter {

    private final AiProperties aiProperties;

    public QueryRewriter(AiProperties aiProperties) {
        this.aiProperties = aiProperties;
    }

    public AdaptiveRagRewriteResult rewrite(String question,
                                             AdaptiveRagDecision decision,
                                             AdaptiveRagVerificationResult previousVerification) {
        String normalized = AdaptiveRagTextSupport.normalize(question);
        LinkedHashSet<String> verificationKeywords = new LinkedHashSet<>(
                AdaptiveRagTextSupport.extractKeywords(
                        normalized, aiProperties.getRag().getAdaptive().getMinKeywordCount() + 6));

        if (previousVerification != null && previousVerification.getMissingKeywords() != null) {
            verificationKeywords.addAll(previousVerification.getMissingKeywords());
        }

        LinkedHashSet<String> retrievalKeywords = new LinkedHashSet<>(verificationKeywords);

        if (decision != null && decision.getRouteType() == RagRouteType.MULTI_HOP) {
            retrievalKeywords.addAll(List.of("对比", "关系", "原因", "流程", "多跳"));
        } else if (decision != null && decision.getRouteType() == RagRouteType.SINGLE_HOP) {
            retrievalKeywords.addAll(List.of("事实", "文档", "答案"));
        }

        List<String> rewrittenKeywords = new ArrayList<>(retrievalKeywords);
        if (rewrittenKeywords.size() > 10) {
            rewrittenKeywords = rewrittenKeywords.subList(0, 10);
        }

        String rewrittenQuery = String.join(" ", rewrittenKeywords);
        if (!StringUtils.hasText(rewrittenQuery)) {
            rewrittenQuery = normalized;
            rewrittenKeywords = List.of(normalized);
        }

        boolean changed = !rewrittenQuery.equals(normalized);
        String reason = previousVerification == null
                ? "initial rewrite"
                : "feedback rewrite: " + previousVerification.getReason();

        return AdaptiveRagRewriteResult.builder()
                .originalQuery(question)
                .rewrittenQuery(rewrittenQuery)
                .keywords(new ArrayList<>(verificationKeywords))
                .changed(changed)
                .reason(reason)
                .build();
    }
}
