package com.aiagent.agent.application;

import com.aiagent.rag.application.AdaptiveRagContext;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatExecutionResult {
    private String answer;
    private AdaptiveRagContext adaptiveRagContext;
    private boolean cacheHit;
    private String responseSource;
    private ReActExecutionTrace reactTrace;
}