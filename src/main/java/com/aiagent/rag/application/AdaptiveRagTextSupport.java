package com.aiagent.rag.application;

import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

final class AdaptiveRagTextSupport {

    private static final Pattern NON_WORD_PATTERN = Pattern.compile("[\\p{Punct}\\p{IsPunctuation}]+");

    private static final Set<String> STOP_WORDS = Set.of(
            "请", "帮我", "麻烦", "一下", "一下子", "详细", "简单", "告诉我", "可以吗", "能不能",
            "帮忙", "这个", "那个", "这些", "那些", "一下吧", "请问", "给我", "谢谢",
            "关于", "相关", "问题", "内容", "一下呢", "帮我看", "帮我想", "帮我做", "帮我写",
            "什么", "是什么", "为什么", "怎么", "如何", "多少", "多久", "哪些", "是否", "能否"
    );

    private static final List<String> DOMAIN_TERMS = List.of(
            "RAG", "Milvus", "BM25", "RRF", "embedding", "Embedding", "召回", "检索", "向量",
            "缓存", "路由", "改写", "验证", "多跳", "对比", "区别", "原因", "流程", "原理",
            "数据", "文档", "上下文", "多路", "融合", "阈值", "语义", "模型", "搜索", "知识",
            "答案", "配置", "参数", "评估", "延迟", "命中率", "准确率", "精确率", "召回率",
            "退款", "到账", "订单", "收货地址", "修改", "商品", "会员", "权益", "专属折扣",
            "发货", "售后", "退货", "换货", "物流", "优惠券", "支付", "时效", "配送", "发票"
    );

    private AdaptiveRagTextSupport() {
    }

    static String normalize(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        return text.trim().replaceAll("\\s+", " ");
    }

    static List<String> extractKeywords(String text, int maxKeywords) {
        LinkedHashSet<String> keywords = new LinkedHashSet<>();
        String normalized = normalize(text);
        if (!StringUtils.hasText(normalized)) {
            return List.of();
        }

        for (String domainTerm : DOMAIN_TERMS) {
            if (normalized.contains(domainTerm)) {
                keywords.add(domainTerm);
                if (keywords.size() >= maxKeywords) {
                    return new ArrayList<>(keywords);
                }
            }
        }

        // 领域词比无分词条件下的中文字符切片更稳定，命中后不再生成噪声 n-gram。
        if (!keywords.isEmpty()) {
            return new ArrayList<>(keywords);
        }

        String cleaned = NON_WORD_PATTERN.matcher(normalized).replaceAll(" ");
        for (String token : cleaned.split("\\s+")) {
            String candidate = token.trim();
            if (!StringUtils.hasText(candidate) || isStopWord(candidate)) {
                continue;
            }
            addCandidate(keywords, candidate, maxKeywords);
            if (keywords.size() >= maxKeywords) {
                return new ArrayList<>(keywords);
            }

            if (candidate.length() >= 4) {
                int windowLimit = Math.min(candidate.length(), 4);
                for (int windowSize = 2; windowSize <= windowLimit; windowSize++) {
                    for (int index = 0; index + windowSize <= candidate.length(); index++) {
                        String slice = candidate.substring(index, index + windowSize);
                        if (isStopWord(slice)) {
                            continue;
                        }
                        addCandidate(keywords, slice, maxKeywords);
                        if (keywords.size() >= maxKeywords) {
                            return new ArrayList<>(keywords);
                        }
                    }
                }
            }
        }

        if (keywords.isEmpty()) {
            keywords.add(normalized);
        }

        return new ArrayList<>(keywords);
    }

    static double overlapScore(List<String> keywords, String text) {
        if (keywords == null || keywords.isEmpty() || !StringUtils.hasText(text)) {
            return 0.0;
        }

        String normalizedText = text.toLowerCase(Locale.ROOT);
        long matched = keywords.stream()
                .filter(keyword -> normalizedText.contains(keyword.toLowerCase(Locale.ROOT)))
                .count();
        return (double) matched / keywords.size();
    }

    static List<String> matchedKeywords(List<String> keywords, String text) {
        if (keywords == null || keywords.isEmpty() || !StringUtils.hasText(text)) {
            return List.of();
        }
        String normalizedText = text.toLowerCase(Locale.ROOT);
        return keywords.stream()
                .filter(keyword -> normalizedText.contains(keyword.toLowerCase(Locale.ROOT)))
                .toList();
    }

    static List<String> missingKeywords(List<String> keywords, String text) {
        if (keywords == null || keywords.isEmpty()) {
            return List.of();
        }
        String normalizedText = StringUtils.hasText(text) ? text.toLowerCase(Locale.ROOT) : "";
        return keywords.stream()
                .filter(keyword -> !normalizedText.contains(keyword.toLowerCase(Locale.ROOT)))
                .toList();
    }

    static boolean containsAny(String text, List<String> keywords) {
        if (!StringUtils.hasText(text) || keywords == null || keywords.isEmpty()) {
            return false;
        }
        String normalizedText = text.toLowerCase(Locale.ROOT);
        return keywords.stream().anyMatch(keyword -> normalizedText.contains(keyword.toLowerCase(Locale.ROOT)));
    }

    private static void addCandidate(Set<String> keywords, String candidate, int maxKeywords) {
        if (keywords.size() >= maxKeywords) {
            return;
        }
        if (candidate.length() == 1) {
            return;
        }
        if (isStopWord(candidate)) {
            return;
        }
        keywords.add(candidate);
    }

    private static boolean isStopWord(String token) {
        return STOP_WORDS.contains(token) || STOP_WORDS.contains(token.toLowerCase(Locale.ROOT));
    }
}
