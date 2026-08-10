package com.aiagent.agent.application;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MultiAgentExecutionResult {
    private String answer;
    private MultiAgentExecutionTrace trace;
}