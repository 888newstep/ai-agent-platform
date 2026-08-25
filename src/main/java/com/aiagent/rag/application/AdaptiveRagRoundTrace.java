package com.aiagent.rag.application;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 单轮检索的完整 trace 记录
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdaptiveRagRoundTrace {
    private int round;
    private String rewrittenQuery;
    private List<String> keywords;
    private boolean queryRewritten;
    private String rewriteReason;

    private List<ChunkTrace> retrievedChunks;
    private int chunkCount;

    private RagVerificationLevel verificationLevel;
    private double verificationScore;
    private double semanticScore;
    private double keywordCoverage;
    private boolean evidenceSufficient;
    private List<String> matchedKeywords;
    private List<String> missingKeywords;
    private String verificationReason;

    private boolean terminal;
    private String terminalReason;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChunkTrace {
        private String chunkId;
        private String qaPairId;
        private String documentId;
        private double score;
        private String category;
        private String question;
        private String retrievalSource;
        private Integer vectorRank;
        private Integer bm25Rank;
        private Double rrfScore;
        private Double semanticRerankScore;
        private String rerankProvider;
        private Double rerankMinScore;
    }
}
