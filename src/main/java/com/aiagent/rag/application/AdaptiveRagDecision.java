package com.aiagent.rag.application;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdaptiveRagDecision {
    private RagRouteType routeType;
    private double confidence;
    private String reason;
    private int plannedRetrievalRounds;
}
