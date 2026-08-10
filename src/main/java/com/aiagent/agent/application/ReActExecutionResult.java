package com.aiagent.agent.application;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReActExecutionResult {
    private String answer;
    private ReActExecutionTrace trace;
}