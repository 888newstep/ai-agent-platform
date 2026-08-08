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
public class AdaptiveRagRewriteResult {
    private String originalQuery;
    private String rewrittenQuery;
    private List<String> keywords;
    private boolean changed;
    private String reason;
}
