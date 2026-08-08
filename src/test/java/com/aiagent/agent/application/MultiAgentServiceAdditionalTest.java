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
class MultiAgentServiceAdditionalTest {
    @Mock private ChatLanguageModel chatLanguageModel;
    @Mock private ToolService toolService;
    @Mock private ReActAgent reActAgent;
    private MultiAgentService service;
    @BeforeEach void setUp() { service = new MultiAgentService(chatLanguageModel, toolService, reActAgent); }

    @Test void shouldExecuteWithLongTask() {
        when(chatLanguageModel.generate(contains("任务规划专家")))
                .thenReturn("SUBTASK 1: task1\nSUBTASK 2: task2");
        when(reActAgent.execute(anyString(), eq(""), eq("")))
                .thenReturn("result1")
                .thenReturn("result2");
        when(chatLanguageModel.generate(contains("结果汇总专家")))
                .thenReturn("Final");
        assertNotNull(service.execute("Complex task", "context"));
    }
    @Test void shouldExecuteWithSpecialChars() {
        when(chatLanguageModel.generate(anyString())).thenReturn("Result!");
        assertNotNull(service.execute("task <special>", ""));
    }
    @Test void shouldHandleMultipleSubtasks() {
        when(chatLanguageModel.generate(contains("任务规划专家")))
                .thenReturn("SUBTASK 1: a\nSUBTASK 2: b\nSUBTASK 3: c");
        when(reActAgent.execute(anyString(), eq(""), eq("")))
                .thenReturn("r1").thenReturn("r2").thenReturn("r3");
        when(chatLanguageModel.generate(contains("结果汇总专家")))
                .thenReturn("Result");
        assertNotNull(service.execute("multi-step task", "ctx"));
    }
}