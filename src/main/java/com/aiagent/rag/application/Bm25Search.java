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
 * BM25 ????????????????????
 *
 * <p>?????????????BM25 ???????????
 * ??????????????? BM25 ??????????????
 */
public class Bm25Search {

    /** BM25 ?? k1?????????? */
    private static final double K1 = 1.2;

    /** BM25 ?? b????????????? */
    private static final double B = 0.75;

    /** ??????? */
    private final List<RetrievalChunk> documents;

    /** ????? */
    private final int docCount;

    /** ??????????? IDF? */
    private final Map<String, Integer> df;

    /** ??????? */
    private final double avgDocLength;

    /** ????????? */
    private final List<Map<String, Integer>> termFrequencies;

    /**
     * ?????????? BM25 ????
     *
     * @param documents ??????
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

            // ?????
            Map<String, Integer> tf = new HashMap<>();
            Set<String> uniqueTerms = new HashSet<>();
            for (String term : terms) {
                tf.merge(term, 1, Integer::sum);
                uniqueTerms.add(term);
            }
            termFrequencies.add(tf);

            // ???????
            for (String term : uniqueTerms) {
                df.merge(term, 1, Integer::sum);
            }
        }
        this.avgDocLength = docCount == 0 ? 0 : totalLength / docCount;
    }

    /**
     * ?? BM25 ???
     *
     * @param query ????
     * @param topK ???????
     * @return ? BM25 ??????????
     */
    public List<RetrievalChunk> search(String query, int topK) {
        if (documents.isEmpty()) {
            return List.of();
        }

        List<String> queryTerms = tokenize(query);
        if (queryTerms.isEmpty()) {
            return List.of();
        }

        // ??????? BM25 ???
        List<ScoredDoc> scoredDocs = new ArrayList<>();
        for (int i = 0; i < docCount; i++) {
            double score = 0;
            Map<String, Integer> tf = termFrequencies.get(i);
            double docLength = tf.values().stream().mapToInt(Integer::intValue).sum();

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
     * ??????????????????????????????
     * ????????????????????????
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
            if (part.length() >= 1) {
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

    /** ????????? */
    private record ScoredDoc(int index, double score) {}
}
