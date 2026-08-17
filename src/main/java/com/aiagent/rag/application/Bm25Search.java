package com.aiagent.rag.application;

import com.aiagent.knowledge.domain.RetrievalChunk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 基于内存倒排统计的 BM25 检索器。
 *
 * <p>初始化阶段计算文档频率和词频，查询阶段根据 BM25 公式对候选片段排序。
 * 中文文本按单字切分，英文和数字按连续词元切分。
 */
public class Bm25Search {

    /** BM25 词频饱和参数。 */
    private static final double K1 = 1.2;

    /** BM25 文档长度归一化参数。 */
    private static final double B = 0.75;

    /** 待检索的文档片段。 */
    private final List<RetrievalChunk> documents;

    /** 文档数量。 */
    private final int docCount;

    /** 每个词项出现过的文档数量，用于计算 IDF。 */
    private final Map<String, Integer> df;

    /** 平均文档长度。 */
    private final double avgDocLength;

    /** 每个文档的词频表。 */
    private final List<Map<String, Integer>> termFrequencies;

    /**
     * 为文档集合构建 BM25 统计信息。
     *
     * @param documents 待建立索引的文档片段
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

            // 统计当前文档的词频。
            Map<String, Integer> tf = new HashMap<>();
            Set<String> uniqueTerms = new HashSet<>();
            for (String term : terms) {
                tf.merge(term, 1, (a, b) -> a + b);
                uniqueTerms.add(term);
            }
            termFrequencies.add(tf);

            // 每个词项在同一文档中只计一次文档频率。
            for (String term : uniqueTerms) {
                df.merge(term, 1, (a, b) -> a + b);
            }
        }
        this.avgDocLength = docCount == 0 ? 0 : totalLength / docCount;
    }

    /**
     * 执行 BM25 检索。
     *
     * @param query 查询文本
     * @param topK 返回结果数量上限
     * @return 按 BM25 分数降序排列的检索结果
     */
    public List<RetrievalChunk> search(String query, int topK) {
        if (documents.isEmpty()) {
            return List.of();
        }

        List<String> queryTerms = tokenize(query);
        if (queryTerms.isEmpty()) {
            return List.of();
        }

        // 计算每篇文档的 BM25 分数。
        List<ScoredDoc> scoredDocs = new ArrayList<>();
        for (int i = 0; i < docCount; i++) {
            double score = 0;
            Map<String, Integer> tf = termFrequencies.get(i);
            double docLength = tf.values().stream().mapToInt(v -> v.intValue()).sum();

            for (String term : queryTerms) {
                Integer termFreq = tf.getOrDefault(term, 0);
                if (termFreq == 0) {
                    continue;
                }

                double docFreq = df.getOrDefault(term, 0);
                double idf = Math.log(1 + (docCount - docFreq + 0.5) / (docFreq + 0.5));

                double numerator = termFreq * (K1 + 1);
                double denominator = termFreq + K1 * (1 - B + B * docLength / avgDocLength);
                score += idf * numerator / denominator;
            }

            if (score > 0) {
                scoredDocs.add(new ScoredDoc(i, score));
            }
        }

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
     * 对中英文混合文本进行轻量分词，避免引入额外分词依赖。
     */
    private List<String> tokenize(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }

        List<String> tokens = new ArrayList<>();
        String[] parts = text.toLowerCase()
                .replaceAll("[\\p{P}\\p{S}\\s]+", " ")
                .split("\\s+");

        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            StringBuilder word = new StringBuilder();
            StringBuilder ideographic = new StringBuilder();
            for (char c : part.toCharArray()) {
                if (Character.isIdeographic(c)) {
                    if (word.length() > 0) {
                        tokens.add(word.toString());
                        word = new StringBuilder();
                    }
                    ideographic.append(c);
                } else if (Character.isLetterOrDigit(c)) {
                    if (ideographic.length() > 0) {
                        addIdeographicTokens(tokens, ideographic.toString());
                        ideographic = new StringBuilder();
                    }
                    word.append(c);
                }
            }
            if (word.length() > 0) {
                tokens.add(word.toString());
            }
            if (ideographic.length() > 0) {
                addIdeographicTokens(tokens, ideographic.toString());
            }
        }

        return tokens;
    }

    /**
     * 中文连续字串按叠加 bigram 产出词元：单字保留，多字生成滑窗二元组。
     * 相比单字切分，bigram 能更精确地对齐查询与文档中的词，减少单字过匹配。
     */
    private void addIdeographicTokens(List<String> tokens, String run) {
        if (run.length() == 1) {
            tokens.add(run);
            return;
        }
        for (int i = 0; i < run.length() - 1; i++) {
            tokens.add(run.substring(i, i + 2));
        }
    }

    /** 带分数的文档位置。 */
    private record ScoredDoc(int index, double score) {}
}
