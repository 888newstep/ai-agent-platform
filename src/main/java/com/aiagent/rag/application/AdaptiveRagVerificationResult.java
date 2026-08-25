package com.aiagent.rag.application;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdaptiveRagVerificationResult {
    private RagVerificationLevel level;
    private double score;
    private double semanticScore;
    private double keywordCoverage;
    private boolean evidenceSufficient;
    private List<String> matchedKeywords;
    private List<String> missingKeywords;
    private int relevantChunkCount;
    private String reason;
}
