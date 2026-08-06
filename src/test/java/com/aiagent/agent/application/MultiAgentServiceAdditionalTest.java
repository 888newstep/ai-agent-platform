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
    private MultiAgentService service;
    @BeforeEach void setUp() { service = new MultiAgentService(chatLanguageModel, toolService); }

    @Test void shouldExecuteWithLongTask() {
        when(chatLanguageModel.generate(anyString())).thenReturn("SUBTASK 1: task1\nSUBTASK 2: task2\nFinal");
        assertNotNull(service.execute("Complex task", "context"));
    }
    @Test void shouldExecuteWithSpecialChars() {
        when(chatLanguageModel.generate(anyString())).thenReturn("Result!");
        assertNotNull(service.execute("task <special>", ""));
    }
    @Test void shouldHandleMultipleSubtasks() {
        when(chatLanguageModel.generate(anyString())).thenReturn("SUBTASK 1: a\nSUBTASK 2: b\nSUBTASK 3: c\nResult");
        assertNotNull(service.execute("multi-step task", "ctx"));
    }
}
