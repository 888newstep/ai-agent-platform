package com.aiagent.agent.application;

import com.aiagent.agent.infrastructure.tool.ToolService;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MultiAgentServiceTest {

    @Mock private ChatLanguageModel chatLanguageModel;
    @Mock private ToolService toolService;
    @Mock private ReActAgent reActAgent;
    private MultiAgentService multiAgentService;

    @BeforeEach
    void setUp() {
        multiAgentService = new MultiAgentService(chatLanguageModel, toolService, reActAgent);
    }

    @Test
    void shouldDelegateWorkerToReActAgent() {
        when(chatLanguageModel.generate(contains("任务规划专家")))
                .thenReturn("SUBTASK 1: 查询用户数据\nSUBTASK 2: 调用API获取配置");
        when(reActAgent.execute(anyString(), eq(""), eq("")))
                .thenReturn("Worker结果1")
                .thenReturn("Worker结果2");
        when(chatLanguageModel.generate(contains("结果汇总专家")))
                .thenReturn("整合后的最终回答");

        String result = multiAgentService.execute("复杂任务", "context");

        assertEquals("整合后的最终回答", result);
        verify(reActAgent, times(2)).execute(anyString(), eq(""), eq(""));
    }

    @Test
    void shouldFallbackToSingleSubtaskWhenNoSubtaskFormat() {
        when(chatLanguageModel.generate(contains("任务规划专家")))
                .thenReturn("没有子任务格式的输出");
        when(reActAgent.execute(anyString(), eq(""), eq("")))
                .thenReturn("单任务结果");
        when(chatLanguageModel.generate(contains("结果汇总专家")))
                .thenReturn("汇总回答");

        String result = multiAgentService.execute("简单任务", "");

        assertEquals("汇总回答", result);
        verify(reActAgent, times(1)).execute(eq("简单任务"), eq(""), eq(""));
    }

    @Test
    void shouldUseSynthesizeFallbackWhenSummaryFails() {
        when(chatLanguageModel.generate(contains("任务规划专家")))
                .thenReturn("SUBTASK 1: 子任务");
        when(reActAgent.execute(anyString(), eq(""), eq("")))
                .thenReturn("Worker结果");
        when(chatLanguageModel.generate(contains("结果汇总专家")))
                .thenThrow(new RuntimeException("Synth error"));

        String result = multiAgentService.execute("任务", "");

        // synthesize catches internally and returns concatenated fallback
        assertNotNull(result);
        assertTrue(result.contains("Worker结果"));
    }

    @Test
    void shouldHandleWorkerFailure() {
        when(chatLanguageModel.generate(contains("任务规划专家")))
                .thenReturn("SUBTASK 1: 子任务A");
        when(reActAgent.execute(anyString(), eq(""), eq("")))
                .thenThrow(new RuntimeException("Worker error"));
        when(chatLanguageModel.generate(contains("结果汇总专家")))
                .thenReturn("汇总结果");

        String result = multiAgentService.execute("任务", "ctx");

        assertNotNull(result);
    }

    @Test
    void shouldHandleEmptyTask() {
        when(chatLanguageModel.generate(contains("任务规划专家")))
                .thenReturn("SUBTASK 1: 处理空任务");
        when(reActAgent.execute(anyString(), eq(""), eq("")))
                .thenReturn("空任务结果");
        when(chatLanguageModel.generate(contains("结果汇总专家")))
                .thenReturn("完成");

        String result = multiAgentService.execute("", "");

        assertNotNull(result);
    }
}