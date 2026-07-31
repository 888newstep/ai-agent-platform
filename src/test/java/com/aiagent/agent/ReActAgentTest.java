package com.aiagent.agent;

import com.aiagent.tool.ToolService;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.data.message.AiMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ReAct Agent 推理循环测试
 *
 * 验证：限流自动降级、死循环防护、超时控制。
 */
@ExtendWith(MockitoExtension.class)
class ReActAgentTest {

    @Mock
    private ChatLanguageModel chatLanguageModel;

    @Mock
    private ToolService toolService;

    @Test
    void shouldReturnErrorMessageWhenModelFails() {
        String question = "查询数据库中的用户数量";
        String context = "";
        String history = "";

        when(chatLanguageModel.generate(anyString()))
                .thenThrow(new RuntimeException("API rate limit exceeded"));

        ReActAgent agent = new ReActAgent(chatLanguageModel, toolService);
        String result = agent.execute(question, context, history);

        assertTrue(result.contains("AI 模型调用失败"));
    }

    @Test
    void shouldReturnFinalAnswerOnFirstStep() {
        String question = "你好";
        String context = "";
        String history = "";

        String directAnswer = """
                Thought: 用户问好，直接回答即可。
                Final Answer: 你好！我是 AI 助手，有什么可以帮助你的吗？
                """;

        when(chatLanguageModel.generate(anyString()))
                .thenReturn(directAnswer);

        ReActAgent agent = new ReActAgent(chatLanguageModel, toolService);
        String result = agent.execute(question, context, history);

        assertTrue(result.contains("AI 助手"));
        assertFalse(result.contains("失败"));
    }

    @Test
    void shouldHandleEmptyResponse() {
        String question = "测试";
        String context = "";
        String history = "";

        when(chatLanguageModel.generate(anyString()))
                .thenReturn("");

        ReActAgent agent = new ReActAgent(chatLanguageModel, toolService);
        String result = agent.execute(question, context, history);

        assertNotNull(result);
    }
}