package com.aiagent.rag.application;

import com.aiagent.infrastructure.config.AiProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Heuristic router that decides whether a question should be answered directly,
 * or should enter single-hop / multi-hop retrieval.
 */
@Component
public class QueryRouter {

    private static final List<String> DIRECT_PATTERNS = List.of(
            "你好", "hello", "hi", "帮我写", "写一个", "生成", "润色", "翻译", "改写",
            "代码", "示例", "模板", "总结这段", "帮我做", "帮我想", "请帮我", "起草", "排版"
    );

    private static final List<String> SINGLE_HOP_PATTERNS = List.of(
            "是什么", "怎么", "哪里", "谁", "多少", "什么时候", "有哪些", "介绍", "说明",
            "流程", "步骤", "配置", "实现", "评估", "召回", "检索", "缓存", "参数", "阈值"
    );

    private static final List<String> MULTI_HOP_PATTERNS = List.of(
            "对比", "区别", "优缺点", "为什么", "原因", "影响", "关系", "联系", "结合",
            "多跳", "如何同时", "如何结合", "方案", "总结", "链路", "演进", "比较"
    );

    private final AiProperties aiProperties;

    public QueryRouter(AiProperties aiProperties) {
        this.aiProperties = aiProperties;
    }

    public AdaptiveRagDecision route(String question) {
        String normalized = AdaptiveRagTextSupport.normalize(question);
        if (!StringUtils.hasText(normalized)) {
            return AdaptiveRagDecision.builder()
                    .routeType(RagRouteType.DIRECT_ANSWER)
                    .confidence(1.0)
                    .reason("empty question")
                    .plannedRetrievalRounds(0)
                    .build();
        }

        int directScore = scorePatterns(normalized, DIRECT_PATTERNS);
        int singleScore = scorePatterns(normalized, SINGLE_HOP_PATTERNS);
        int multiScore = scorePatterns(normalized, MULTI_HOP_PATTERNS);
        boolean shortQuestion = normalized.length() <= 8;

        if (shortQuestion) {
            directScore += 1;
        }
        if (AdaptiveRagTextSupport.containsAny(normalized, List.of("帮我", "写", "生成", "润色", "翻译", "代码"))) {
            directScore += 2;
        }

        RagRouteType routeType;
        int plannedRounds = 0;
        List<String> reasons = new ArrayList<>();

        if (multiScore > 0 && multiScore >= singleScore) {
            routeType = RagRouteType.MULTI_HOP;
            plannedRounds = aiProperties.getRag().getAdaptive().getMaxRetrievalRounds();
            reasons.addAll(matchedPatterns(normalized, MULTI_HOP_PATTERNS));
            reasons.addAll(matchedPatterns(normalized, SINGLE_HOP_PATTERNS));
        } else if (singleScore > 0) {
            routeType = RagRouteType.SINGLE_HOP;
            plannedRounds = 1;
            reasons.addAll(matchedPatterns(normalized, SINGLE_HOP_PATTERNS));
        } else if (directScore > 0) {
            routeType = RagRouteType.DIRECT_ANSWER;
            reasons.addAll(matchedPatterns(normalized, DIRECT_PATTERNS));
            if (shortQuestion) {
                reasons.add("short question");
            }
        } else {
            routeType = RagRouteType.DIRECT_ANSWER;
            reasons.add("fallback to direct answer");
        }

        double confidence = confidence(routeType, directScore, singleScore, multiScore);
        if (reasons.isEmpty()) {
            reasons.add("heuristic route");
        }

        return AdaptiveRagDecision.builder()
                .routeType(routeType)
                .confidence(confidence)
                .reason(String.join(", ", reasons))
                .plannedRetrievalRounds(plannedRounds)
                .build();
    }

    private int scorePatterns(String question, List<String> patterns) {
        return (int) patterns.stream().filter(question::contains).count();
    }

    private List<String> matchedPatterns(String question, List<String> patterns) {
        return patterns.stream()
                .filter(question::contains)
                .toList();
    }

    private double confidence(RagRouteType routeType, int directScore, int singleScore, int multiScore) {
        int maxScore = Math.max(directScore, Math.max(singleScore, multiScore));
        if (routeType == RagRouteType.DIRECT_ANSWER && maxScore == 0) {
            return 0.75;
        }
        return Math.min(1.0, 0.35 + (maxScore * 0.2));
    }
}
