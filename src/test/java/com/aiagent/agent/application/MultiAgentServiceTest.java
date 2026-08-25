package com.aiagent.agent.application;

import com.aiagent.infrastructure.metrics.PlatformMetricsService;
import dev.langchain4j.model.chat.ChatLanguageModel;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class MultiAgentServiceTest {

    @Mock private ChatLanguageModel chatLanguageModel;
    @Mock private ReActAgent reActAgent;
    @Mock private PlatformMetricsService metricsService;
    private MultiAgentService multiAgentService;

    @BeforeEach
    void setUp() {
        lenient().when(metricsService.startSample()).thenReturn(Timer.start());
        multiAgentService = new MultiAgentService(chatLanguageModel, reActAgent, metricsService);
    }

    @Test
    void shouldDelegateWorkerToReActAgent() {
        when(chatLanguageModel.generate(contains("TASK PLANNER")))
                .thenReturn("SUBTASK 1: 查询用户数据\nSUBTASK 2: 调用API获取配置");
        when(reActAgent.executeDetailed(anyString(), eq("context"), eq("")))
                .thenReturn(workerResult("worker-1"))
                .thenReturn(workerResult("worker-2"));
        when(chatLanguageModel.generate(contains("RESULT SYNTHESIZER")))
                .thenReturn("final summary");

        String result = multiAgentService.execute("复杂任务", "context");

        assertEquals("final summary", result);
        verify(reActAgent, times(2)).executeDetailed(anyString(), eq("context"), eq(""));
    }

    @Test
    void shouldReturnDetailedTrace() {
        when(chatLanguageModel.generate(contains("TASK PLANNER")))
                .thenReturn("SUBTASK 1: 查询用户数据\nSUBTASK 2: 获取配置");
        when(reActAgent.executeDetailed(anyString(), eq("context"), eq("")))
                .thenReturn(workerResult("worker-1"))
                .thenReturn(workerResult("worker-2"));
        when(chatLanguageModel.generate(contains("RESULT SYNTHESIZER")))
                .thenReturn("merged result");

        MultiAgentExecutionResult result = multiAgentService.executeDetailed("复杂任务", "context");

        assertEquals("merged result", result.getAnswer());
        assertEquals("completed", result.getTrace().getStopReason());
        assertEquals(2, result.getTrace().getSubtaskCount());
        assertEquals(2, result.getTrace().getWorkers().size());
        assertNotNull(result.getTrace().getWorkers().get(0).getReactTrace());
        verify(metricsService).recordMultiAgentTrace(eq("completed"), eq(2), eq(0), eq(false), eq(false), eq(true), any());
    }

    @Test
    void shouldFallbackToSingleSubtaskWhenNoSubtaskFormat() {
        when(chatLanguageModel.generate(contains("TASK PLANNER")))
                .thenReturn("no structured subtasks");
        when(reActAgent.executeDetailed(anyString(), eq(""), eq("")))
                .thenReturn(workerResult("single result"));
        when(chatLanguageModel.generate(contains("RESULT SYNTHESIZER")))
                .thenReturn("merged answer");

        String result = multiAgentService.execute("简单任务", "");

        assertEquals("merged answer", result);
        verify(reActAgent, times(1)).executeDetailed(eq("简单任务"), eq(""), eq(""));
    }

    @Test
    void shouldUseSynthesizeFallbackWhenSummaryFails() {
        when(chatLanguageModel.generate(contains("TASK PLANNER")))
                .thenReturn("SUBTASK 1: 子任务");
        when(reActAgent.executeDetailed(anyString(), eq(""), eq("")))
                .thenReturn(workerResult("worker result"));
        when(chatLanguageModel.generate(contains("RESULT SYNTHESIZER")))
                .thenThrow(new RuntimeException("Synth error"));

        String result = multiAgentService.execute("任务", "");

        assertNotNull(result);
        assertTrue(result.contains("worker result"));
    }

    @Test
    void shouldHandleWorkerFailure() {
        when(chatLanguageModel.generate(contains("TASK PLANNER")))
                .thenReturn("SUBTASK 1: 子任务A");
        when(reActAgent.executeDetailed(anyString(), eq("ctx"), eq("")))
                .thenThrow(new RuntimeException("Worker error"));
        when(chatLanguageModel.generate(contains("RESULT SYNTHESIZER")))
                .thenReturn("summary");

        String result = multiAgentService.execute("任务", "ctx");

        assertNotNull(result);
    }

    @Test
    void shouldHandleEmptyTask() {
        when(chatLanguageModel.generate(contains("TASK PLANNER")))
                .thenReturn("SUBTASK 1: 处理空任务");
        when(reActAgent.executeDetailed(anyString(), eq(""), eq("")))
                .thenReturn(workerResult("empty task result"));
        when(chatLanguageModel.generate(contains("RESULT SYNTHESIZER")))
                .thenReturn("done");

        String result = multiAgentService.execute("", "");

        assertNotNull(result);
    }

    private ReActExecutionResult workerResult(String answer) {
        return ReActExecutionResult.builder()
                .answer(answer)
                .trace(ReActExecutionTrace.builder()
                        .stopReason("final_answer")
                        .completed(true)
                        .stepCount(1)
                        .steps(java.util.List.of())
                        .build())
                .build();
    }
}
