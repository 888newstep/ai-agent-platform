package com.aiagent.rag.application;

import com.aiagent.knowledge.domain.RetrievalChunk;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.stream.Collectors;

/**
 * BM25 关键词检索 — 多路召回中的"关键词路"
 *
 * <p>BM25 是一种基于词频和逆文档频率的排序函数，与向量检索互补。
 * 向量检索擅长语义相似，BM25 擅长关键词精确匹配。
 *
 * <p>适用场景：向量检索未命中但用户问题包含精确关键词时，
 * BM25 可以兜底召回。
 */
@Slf4j
public class Bm25Search {

    /** BM25 参数 k1（控制词频饱和度） */
    private static final double K1 = 1.2;

    /** BM25 参数 b（控制文档长度归一化） */
    private static final double B = 0.75;

    /** 文档集合 */
    private final List<RetrievalChunk> documents;

    /** 文档总数 */
    private final int docCount;

    /** 每个词在多少篇文档中出现（用于计算 IDF） */
    private final Map<String, Integer> df;

    /** 平均文档长度 */
    private final double avgDocLength;

    /** 所有文档的词频列表 */
    private final List<Map<String, Integer>> termFrequencies;

    /**
     * 创建一个 BM25 检索器
     *
     * @param documents 文档集合
     */
    public Bm25Search(List<RetrievalChunk> documents) {
        this.documents = documents;
        this.docCount = documents.size();
        this.df = new HashMap<>();
        this.termFrequencies = new ArrayList<>();

        double totalLength = 0;
        for (int i = 0; i < documents.size(); i++) {
            String content = documents.get(i).getContent();
            List<String> terms = tokenize(content);
            totalLength += terms.size();

            // 统计词频
            Map<String, Integer> tf = new HashMap<>();
            Set<String> uniqueTerms = new HashSet<>();
            for (String term : terms) {
                tf.merge(term, 1, Integer::sum);
                uniqueTerms.add(term);
            }
            termFrequencies.add(tf);

            // 统计文档频率
            for (String term : uniqueTerms) {
                df.merge(term, 1, Integer::sum);
            }
        }
        this.avgDocLength = docCount == 0 ? 0 : totalLength / docCount;
    }

    /**
     * 执行 BM25 检索
     *
     * @param query  查询文本
     * @param topK   返回 topK 条结果
     * @return 排序后的文档片段
     */
    public List<RetrievalChunk> search(String query, int topK) {
        if (documents.isEmpty()) {
            return List.of();
        }

        List<String> queryTerms = tokenize(query);
        if (queryTerms.isEmpty()) {
            return List.of();
        }

        // 计算每个文档的 BM25 分数
        List<ScoredDoc> scoredDocs = new ArrayList<>();
        for (int i = 0; i < docCount; i++) {
            double score = 0;
            Map<String, Integer> tf = termFrequencies.get(i);
            double docLength = tf.values().stream().mapToInt(Integer::intValue).sum();

            for (String term : queryTerms) {
                Integer termFreq = tf.getOrDefault(term, 0);
                if (termFreq == 0) continue;

                // IDF
                Integer docFreq = df.getOrDefault(term, 0);
                double idf = Math.log(1 + (docCount - docFreq + 0.5) / (docFreq + 0.5));

                // BM25 分数
                double numerator = termFreq * (K1 + 1);
                double denominator = termFreq + K1 * (1 - B + B * docLength / avgDocLength);
                score += idf * numerator / denominator;
            }

            if (score > 0) {
                scoredDocs.add(new ScoredDoc(i, score));
            }
        }

        // 排序取 topK
        return scoredDocs.stream()
                .sorted((a, b) -> Double.compare(b.score, a.score))
                .limit(topK)
                .map(sd -> {
                    RetrievalChunk original = documents.get(sd.index);
                    return RetrievalChunk.builder()
                            .id(original.getId())
                            .content(original.getContent())
                            .score(sd.score)
                            .metadata(original.getMetadata())
                            .build();
                })
                .collect(Collectors.toList());
    }

    /**
     * 简单中文分词（按非中文/非字母字符分割）
     * 实际生产环境建议使用 HanLP、jieba 等分词器
     */
    private List<String> tokenize(String text) {
        if (text == null || text.isEmpty()) return List.of();

        List<String> tokens = new ArrayList<>();
        // 按空白字符和标点分割
        String[] parts = text.toLowerCase()
                .replaceAll("[\\p{P}\\p{S}\\s]+", " ")
                .split("\\s+");

        for (String part : parts) {
            if (part.length() >= 1) {
                // 对中文按字切分，对英文按词保留
                StringBuilder current = new StringBuilder();
                for (char c : part.toCharArray()) {
                    if (Character.isIdeographic(c)) {
                        if (current.length() > 0) {
                            tokens.add(current.toString());
                            current = new StringBuilder();
                        }
                        tokens.add(String.valueOf(c));
                    } else if (Character.isLetterOrDigit(c)) {
                        current.append(c);
                    }
                }
                if (current.length() > 0) {
                    tokens.add(current.toString());
                }
            }
        }

        return tokens;
    }

    /** 带分数的文档索引 */
    private record ScoredDoc(int index, double score) {}
}