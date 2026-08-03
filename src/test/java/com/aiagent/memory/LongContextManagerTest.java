package com.aiagent.memory;

import com.aiagent.config.AiProperties;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LongContextManagerTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ChatLanguageModel chatLanguageModel;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    private AiProperties aiProperties;
    private LongContextManager manager;

    @BeforeEach
    void setUp() {
        aiProperties = new AiProperties();
        aiProperties.getSession().setTtl(86400);
        aiProperties.getSession().setMaxMessages(100);
        manager = new LongContextManager(chatLanguageModel, redisTemplate, aiProperties);
    }

    @Test
    void shouldSaveMessageWithoutSummaryWhenBelowThreshold() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        List<Map<String, String>> existingMessages = new ArrayList<>();
        when(valueOperations.get("ai:history:session-1")).thenReturn(existingMessages);

        manager.saveMessageAndMaybeSummarize("session-1", "user", "你好");

        verify(valueOperations).set(eq("ai:history:session-1"), any(), eq(86400L), eq(java.util.concurrent.TimeUnit.SECONDS));
        verify(chatLanguageModel, never()).generate(anyString());
    }

    @Test
    void shouldGenerateSummaryWhenReachingThreshold() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        List<Map<String, String>> messages = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            Map<String, String> msg = new HashMap<>();
            msg.put("role", i % 2 == 0 ? "user" : "assistant");
            msg.put("content", "消息" + i);
            messages.add(msg);
        }
        when(valueOperations.get("ai:history:session-1")).thenReturn(messages);
        when(chatLanguageModel.generate(anyString())).thenReturn("生成的摘要");
        when(valueOperations.get("ai:summary:session-1")).thenReturn(null);

        manager.saveMessageAndMaybeSummarize("session-1", "user", "第10条消息");

        verify(chatLanguageModel).generate(anyString());
        verify(valueOperations).set(eq("ai:summary:session-1"), any(), eq(86400L), eq(java.util.concurrent.TimeUnit.SECONDS));
    }

    @Test
    void shouldReturnRecentMessagesAsOptimizedContext() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        List<Map<String, String>> messages = new ArrayList<>();
        Map<String, String> msg1 = new HashMap<>();
        msg1.put("role", "user");
        msg1.put("content", "问题1");
        messages.add(msg1);
        Map<String, String> msg2 = new HashMap<>();
        msg2.put("role", "assistant");
        msg2.put("content", "回答1");
        messages.add(msg2);
        when(valueOperations.get("ai:history:session-1")).thenReturn(messages);
        when(valueOperations.get("ai:summary:session-1")).thenReturn(null);

        String context = manager.getOptimizedContext("session-1", "新问题");

        assertThat(context).contains("问题1").contains("回答1");
    }

    @Test
    void shouldClearSessionData() {
        manager.clearSession("session-1");

        verify(redisTemplate).delete("ai:history:session-1");
        verify(redisTemplate).delete("ai:summary:session-1");
    }
}
