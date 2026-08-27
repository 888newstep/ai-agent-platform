package com.aiagent.ecommerce.application;

import com.aiagent.ecommerce.config.EcommerceProperties;
import com.aiagent.shared.data.DataCleaner;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 电商客服 QA 意图分类器（关键词规则版）。
 *
 * 设计说明：
 * - 训练数据 system prompt 无意图标签，采用关键词规则分类（零成本、可解释、可配置）
 * - 分类对齐评测 silver-v2 的 6 类意图 + other 兜底
 * - 评分：question 命中权重 2，answer 命中权重 1，取最高分；全部未命中 → other
 */
@Component
@RequiredArgsConstructor
public class QaClassifier {

    public static final String OTHER = "other";
    private static final int QUESTION_WEIGHT = 2;
    private static final int ANSWER_WEIGHT = 1;

    private final EcommerceProperties ecommerceProperties;

    /** 对一条 QA 对做意图分类。 */
    public String classify(String question, String answer) {
        EcommerceProperties.Classifier classifier = ecommerceProperties.getImportConfig().getClassifier();
        if (classifier == null || !classifier.isEnabled() || classifier.getKeywords().isEmpty()) {
            return OTHER;
        }
        String q = DataCleaner.normalize(question);
        String a = DataCleaner.normalize(answer);

        String best = OTHER;
        int bestScore = 0;
        for (Map.Entry<String, List<String>> entry : classifier.getKeywords().entrySet()) {
            String category = entry.getKey();
            int score = 0;
            for (String kw : entry.getValue()) {
                if (kw.isEmpty()) {
                    continue;
                }
                if (q.contains(kw)) {
                    score += QUESTION_WEIGHT;
                }
                if (!a.isEmpty() && a.contains(kw)) {
                    score += ANSWER_WEIGHT;
                }
            }
            if (score > bestScore) {
                bestScore = score;
                best = category;
            }
        }
        return best;
    }
}
