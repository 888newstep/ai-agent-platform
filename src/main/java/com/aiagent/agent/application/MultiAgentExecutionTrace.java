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
public class MultiAgentExecutionTrace {
    private String task;
    private int subtaskCount;
    private boolean singleAgentFallback;
    private boolean synthesisFallback;
    private String stopReason;
    private long totalLatencyMs;
    private List<String> subtasks;
    private List<WorkerTrace> workers;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WorkerTrace {
        private int index;
        private String subtask;
        private String result;
        private String status;
        private long latencyMs;
        private ReActExecutionTrace reactTrace;
    }
}