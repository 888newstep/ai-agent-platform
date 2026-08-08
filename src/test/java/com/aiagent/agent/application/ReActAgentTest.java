package com.aiagent.agent.application;

import com.aiagent.agent.infrastructure.tool.ToolService;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReActAgentTest {

    @Mock
    private ChatLanguageModel chatLanguageModel;

    @Mock
    private ToolService toolService;

    @Test
    void shouldReturnErrorMessageWhenModelFails() {
        when(chatLanguageModel.generate(anyString()))
                .thenThrow(new RuntimeException("API rate limit exceeded"));

        ReActAgent agent = new ReActAgent(chatLanguageModel, toolService);
        String result = agent.execute("查询数据库中的用户数量", "", "");

        assertTrue(result.contains("AI 模型调用失败"));
    }

    @Test
    void shouldReturnFinalAnswerOnFirstStep() {
        when(chatLanguageModel.generate(anyString()))
                .thenReturn("""
                        Thought: 用户问好，直接回答即可。
                        Final Answer: 你好！我是 AI 助手，有什么可以帮你的吗？
                        """);

        ReActAgent agent = new ReActAgent(chatLanguageModel, toolService);
        String result = agent.execute("你好", "", "");

        assertTrue(result.contains("AI 助手"));
        assertFalse(result.contains("失败"));
    }

    @Test
    void shouldReturnStructuredJsonFinalAnswer() {
        when(chatLanguageModel.generate(anyString())).thenReturn("""
                {
                  "thought": "直接回答即可",
                  "action": null,
                  "actionInput": null,
                  "finalAnswer": "这是结构化回答"
                }
                """);

        ReActAgent agent = new ReActAgent(chatLanguageModel, toolService);
        String result = agent.execute("测试", "", "");

        assertEquals("这是结构化回答", result);
    }

    @Test
    void shouldHandleEmptyResponse() {
        when(chatLanguageModel.generate(anyString())).thenReturn("");

        ReActAgent agent = new ReActAgent(chatLanguageModel, toolService);
        String result = agent.execute("测试", "", "");

        assertNotNull(result);
    }

    @Test
    void shouldExecuteDatabaseQueryTool() {
        when(chatLanguageModel.generate(anyString()))
                .thenReturn("""
                        Thought: 需要查询数据库获取用户数量
                        Action: query_database
                        Action Input: SELECT COUNT(*) FROM users
                        """);
        when(toolService.queryDatabase("SELECT COUNT(*) FROM users"))
                .thenReturn("Found 1 results\ncount: 42");

        ReActAgent agent = new ReActAgent(chatLanguageModel, toolService);
        String result = agent.execute("查询数据库中的用户数量", "", "");

        verify(toolService, atLeastOnce()).queryDatabase("SELECT COUNT(*) FROM users");
        assertNotNull(result);
    }

    @Test
    void shouldExecuteStructuredDatabaseQueryTool() {
        when(chatLanguageModel.generate(anyString()))
                .thenReturn("""
                        {
                          "thought": "先查库",
                          "action": "query_database",
                          "actionInput": "SELECT COUNT(*) FROM users",
                          "finalAnswer": null
                        }
                        """);
        when(toolService.queryDatabase("SELECT COUNT(*) FROM users")).thenReturn("count: 42");

        ReActAgent agent = new ReActAgent(chatLanguageModel, toolService);
        String result = agent.execute("查用户数", "", "");

        verify(toolService, atLeastOnce()).queryDatabase("SELECT COUNT(*) FROM users");
        assertNotNull(result);
    }

    @Test
    void shouldExecuteStructuredExternalApiTool() {
        when(chatLanguageModel.generate(anyString()))
                .thenReturn("""
                        {
                          "thought": "调用外部接口",
                          "action": "call_external_api",
                          "actionInput": {
                            "url": "https://api.example.com/orders",
                            "method": "GET",
                            "body": ""
                          },
                          "finalAnswer": null
                        }
                        """);
        when(toolService.callExternalApi("https://api.example.com/orders", "GET", "")).thenReturn("Status: 200");

        ReActAgent agent = new ReActAgent(chatLanguageModel, toolService);
        String result = agent.execute("查订单", "", "");

        verify(toolService, atLeastOnce()).callExternalApi("https://api.example.com/orders", "GET", "");
        assertNotNull(result);
    }

    @Test
    void shouldHandleUnknownTool() {
        when(chatLanguageModel.generate(anyString()))
                .thenReturn("""
                        Thought: 尝试调用未知工具
                        Action: unknown_tool
                        Action Input: some input
                        """);

        ReActAgent agent = new ReActAgent(chatLanguageModel, toolService);
        String result = agent.execute("测试未知工具", "", "");

        assertNotNull(result);
        assertTrue(result.contains("未知工具") || result.contains("尝试了多种方法"));
    }

    @Test
    void shouldDetectRepeatingObservations() {
        when(chatLanguageModel.generate(anyString()))
                .thenReturn("""
                        Thought: 继续尝试
                        Action: query_database
                        Action Input: SELECT 1
                        """);
        when(toolService.queryDatabase("SELECT 1")).thenReturn("same result");

        ReActAgent agent = new ReActAgent(chatLanguageModel, toolService);
        String result = agent.execute("测试死循环检测", "", "");

        assertNotNull(result);
        assertTrue(result.contains("最后获取到的信息") || result.contains("same result"));
    }

    @Test
    void shouldBuildUserPromptWithContextAndHistory() {
        when(chatLanguageModel.generate(anyString()))
                .thenReturn("""
                        Thought: 直接回答
                        Final Answer: 测试回答
                        """);

        ReActAgent agent = new ReActAgent(chatLanguageModel, toolService);
        String result = agent.execute("测试问题", "相关上下文信息", "user: 你好\nassistant: 你好");

        assertNotNull(result);
        verify(chatLanguageModel).generate(contains("相关上下文信息"));
        verify(chatLanguageModel).generate(contains("你好"));
    }
}
