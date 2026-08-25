package com.aiagent.rag.application;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnswerSupportResult {
    private boolean supported;
    private double score;
    private double supportedClaimRatio;
    private int claimCount;
    private int supportedClaimCount;
    private boolean numbersSupported;
    private String reason;

    public static AnswerSupportResult skipped() {
        return AnswerSupportResult.builder()
                .supported(true)
                .score(1.0)
                .supportedClaimRatio(1.0)
                .numbersSupported(true)
                .reason("answer support verification disabled")
                .build();
    }
}
