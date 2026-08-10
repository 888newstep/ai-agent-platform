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
public class AdaptiveRagContext {
    private String context;
    private RagRouteType routeType;
    private String originalQuery;
    private String rewrittenQuery;
    private String decisionReason;
    private double decisionConfidence;
    private String verificationReason;
    private RagVerificationLevel verificationLevel;
    private int retrievalRounds;
    private int chunkCount;
    private boolean rewritten;
    private boolean verified;
    private boolean usedAdaptive;

    /** 每轮自适应检索的结构化 trace。 */
    private List<AdaptiveRagRoundTrace> roundTraces;

    /** 本次决策的结束原因。 */
    private String endReason;

    public static AdaptiveRagContext empty(String question) {
        return AdaptiveRagContext.builder()
                .context("")
                .routeType(RagRouteType.DIRECT_ANSWER)
                .originalQuery(question)
                .rewrittenQuery(question)
                .decisionReason("adaptive rag disabled or direct answer route")
                .decisionConfidence(1.0)
                .verificationReason("skipped")
                .verificationLevel(RagVerificationLevel.NONE)
                .retrievalRounds(0)
                .chunkCount(0)
                .rewritten(false)
                .verified(false)
                .usedAdaptive(false)
                .roundTraces(List.of())
                .endReason("direct_answer_no_retrieval")
                .build();
    }
}
