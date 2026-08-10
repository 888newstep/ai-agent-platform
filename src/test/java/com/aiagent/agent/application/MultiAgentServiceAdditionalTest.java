package com.aiagent.agent.application;

import com.aiagent.agent.infrastructure.tool.ToolService;
import com.aiagent.infrastructure.metrics.PlatformMetricsService;
import dev.langchain4j.model.chat.ChatLanguageModel;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class MultiAgentServiceAdditionalTest {
    @Mock private ChatLanguageModel chatLanguageModel;
    @Mock private ToolService toolService;
    @Mock private ReActAgent reActAgent;
    @Mock private PlatformMetricsService metricsService;
    private MultiAgentService service;

    @BeforeEach
    void setUp() {
        lenient().when(metricsService.startSample()).thenReturn(Timer.start());
        service = new MultiAgentService(chatLanguageModel, toolService, reActAgent, metricsService);
    }

    @Test
    void shouldExecuteWithLongTask() {
        when(chatLanguageModel.generate(contains("TASK PLANNER")))
                .thenReturn("SUBTASK 1: task1\nSUBTASK 2: task2");
        when(reActAgent.execute(anyString(), eq(""), eq("")))
                .thenReturn("result1")
                .thenReturn("result2");
        when(chatLanguageModel.generate(contains("RESULT SYNTHESIZER")))
                .thenReturn("Final");
        assertNotNull(service.execute("Complex task", "context"));
    }

    @Test
    void shouldExecuteWithSpecialChars() {
        when(chatLanguageModel.generate(anyString())).thenReturn("Result!");
        assertNotNull(service.execute("task <special>", ""));
    }

    @Test
    void shouldHandleMultipleSubtasks() {
        when(chatLanguageModel.generate(contains("TASK PLANNER")))
                .thenReturn("SUBTASK 1: a\nSUBTASK 2: b\nSUBTASK 3: c");
        when(reActAgent.execute(anyString(), eq(""), eq("")))
                .thenReturn("r1").thenReturn("r2").thenReturn("r3");
        when(chatLanguageModel.generate(contains("RESULT SYNTHESIZER")))
                .thenReturn("Result");
        assertNotNull(service.execute("multi-step task", "ctx"));
    }
}