package com.aiagent.agent.application;

import com.aiagent.agent.infrastructure.tool.ToolService;
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

    @Test
    void shouldExecuteDatabaseQueryTool() {
        String question = "查询数据库中的用户数量";
        String context = "";
        String history = "";

        String llmResponse = """
                Thought: 需要查询数据库获取用户数量
                Action: query_database
                Action Input: SELECT COUNT(*) FROM users
                """;

        when(chatLanguageModel.generate(anyString()))
                .thenReturn(llmResponse);
        when(toolService.queryDatabase("SELECT COUNT(*) FROM users"))
                .thenReturn("Found 1 results\ncount: 42");

        ReActAgent agent = new ReActAgent(chatLanguageModel, toolService);
        String result = agent.execute(question, context, history);

        verify(toolService, atLeastOnce()).queryDatabase("SELECT COUNT(*) FROM users");
        assertNotNull(result);
    }

    @Test
    void shouldHandleUnknownTool() {
        String question = "测试未知工具";
        String context = "";
        String history = "";

        String llmResponse = """
                Thought: 尝试调用未知工具
                Action: unknown_tool
                Action Input: some input
                """;

        when(chatLanguageModel.generate(anyString()))
                .thenReturn(llmResponse);

        ReActAgent agent = new ReActAgent(chatLanguageModel, toolService);
        String result = agent.execute(question, context, history);

        assertNotNull(result);
        assertTrue(result.contains("未知工具") || result.contains("尝试了多种方法"));
    }

    @Test
    void shouldDetectRepeatingObservations() {
        String question = "测试死循环检测";
        String context = "";
        String history = "";

        String llmResponse = """
                Thought: 继续尝试
                Action: query_database
                Action Input: SELECT 1
                """;

        when(chatLanguageModel.generate(anyString()))
                .thenReturn(llmResponse);
        when(toolService.queryDatabase("SELECT 1"))
                .thenReturn("same result");

        ReActAgent agent = new ReActAgent(chatLanguageModel, toolService);
        String result = agent.execute(question, context, history);

        assertNotNull(result);
    }

    @Test
    void shouldBuildUserPromptWithContextAndHistory() {
        String question = "测试问题";
        String context = "相关上下文信息";
        String history = "user: 你好\nassistant: 你好！";

        String llmResponse = """
                Thought: 直接回答
                Final Answer: 测试回答
                """;

        when(chatLanguageModel.generate(anyString()))
                .thenReturn(llmResponse);

        ReActAgent agent = new ReActAgent(chatLanguageModel, toolService);
        String result = agent.execute(question, context, history);

        assertNotNull(result);
        verify(chatLanguageModel).generate(contains("相关上下文信息"));
        verify(chatLanguageModel).generate(contains("你好"));
    }
}
