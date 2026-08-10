package com.aiagent.agent.application;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReActExecutionTrace {
    private String question;
    private int stepCount;
    private long totalLatencyMs;
    private String stopReason;
    private boolean completed;
    private List<StepTrace> steps;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StepTrace {
        private int step;
        private String thought;
        private String action;
        private String actionInput;
        private String observation;
        private String finalAnswer;
        private long stepLatencyMs;
        private String toolStatus;
    }
}