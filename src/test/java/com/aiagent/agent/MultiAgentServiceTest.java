package com.aiagent.agent;

import com.aiagent.tool.ToolService;
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
    private MultiAgentService multiAgentService;

    @BeforeEach void setUp() {
        multiAgentService = new MultiAgentService(chatLanguageModel, toolService);
    }

    @Test void shouldExecuteSimpleTask() {
        when(chatLanguageModel.generate(anyString())).thenReturn("Result");
        String result = multiAgentService.execute("Summarize", "context");
        assertNotNull(result);
    }
    @Test void shouldHandleEmptyTask() {
        when(chatLanguageModel.generate(anyString())).thenReturn("Result");
        String result = multiAgentService.execute("", "");
        assertNotNull(result);
    }
    @Test void shouldHandleEmptyContext() {
        when(chatLanguageModel.generate(anyString())).thenReturn("Result");
        String result = multiAgentService.execute("task", "");
        assertNotNull(result);
    }
}
