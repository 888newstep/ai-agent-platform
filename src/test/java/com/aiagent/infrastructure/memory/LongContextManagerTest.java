package com.aiagent.infrastructure.memory;

import com.aiagent.infrastructure.config.AiProperties;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
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
    private EmbeddingModel embeddingModel;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    private AiProperties aiProperties;
    private LongContextManager manager;

    @BeforeEach
    void setUp() {
        aiProperties = new AiProperties();
        aiProperties.getSession().setTtl(86400);
        aiProperties.getSession().setMaxMessages(100);
        manager = new LongContextManager(chatLanguageModel, embeddingModel, redisTemplate, aiProperties);
    }

    @Test
    void shouldSaveMessageWithoutSummaryWhenBelowThreshold() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("ai:history:session-1")).thenReturn(new ArrayList<>());

        manager.saveMessageAndMaybeSummarize("session-1", "user", "你好");

        verify(valueOperations).set(eq("ai:history:session-1"), any(), eq(86400L), eq(java.util.concurrent.TimeUnit.SECONDS));
        verify(chatLanguageModel, never()).generate(anyString());
        verify(embeddingModel, never()).embed(anyString());
    }

    @Test
    void shouldGenerateSummaryWhenReachingThreshold() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        List<Map<String, String>> messages = new ArrayList<>();
        for (int index = 0; index < 9; index++) {
            Map<String, String> message = new HashMap<>();
            message.put("role", index % 2 == 0 ? "user" : "assistant");
            message.put("content", "消息" + index);
            messages.add(message);
        }
        when(valueOperations.get("ai:history:session-1")).thenReturn(messages);
        when(valueOperations.get("ai:summary:session-1")).thenReturn(null);
        when(chatLanguageModel.generate(anyString())).thenReturn("生成的摘要");
        when(embeddingModel.embed("生成的摘要")).thenReturn(new Response<>(new Embedding(new float[]{1.0f, 0.2f})));

        manager.saveMessageAndMaybeSummarize("session-1", "user", "第10条消息");

        verify(chatLanguageModel).generate(anyString());
        verify(embeddingModel).embed("生成的摘要");
        verify(valueOperations).set(eq("ai:summary:session-1"), any(), eq(86400L), eq(java.util.concurrent.TimeUnit.SECONDS));
    }

    @Test
    void shouldReturnRecentMessagesAsOptimizedContext() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "user", "content", "问题1"));
        messages.add(Map.of("role", "assistant", "content", "回答1"));
        when(valueOperations.get("ai:history:session-1")).thenReturn(messages);
        when(valueOperations.get("ai:summary:session-1")).thenReturn(null);

        String context = manager.getOptimizedContext("session-1", "新问题");

        assertThat(context).contains("问题1").contains("回答1");
        verify(embeddingModel, never()).embed(anyString());
    }

    @Test
    void shouldRetrieveRelevantSummariesByEmbeddingSimilarity() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("ai:history:session-1")).thenReturn(null);
        when(valueOperations.get("ai:summary:session-1")).thenReturn(List.of(
                summary("退款规则说明", new float[]{1.0f, 0.0f}),
                summary("天气播报", new float[]{0.0f, 1.0f})
        ));
        when(embeddingModel.embed("退款怎么处理")).thenReturn(new Response<>(new Embedding(new float[]{0.95f, 0.05f})));

        String context = manager.getOptimizedContext("session-1", "退款怎么处理");

        assertThat(context).contains("退款规则说明");
        assertThat(context).doesNotContain("天气播报");
    }

    @Test
    void shouldFallbackToKeywordMatchForLegacySummariesWithoutEmbedding() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("ai:history:session-1")).thenReturn(null);
        when(valueOperations.get("ai:summary:session-1")).thenReturn(List.of(
                legacySummary("退款流程和售后规则"),
                legacySummary("天气播报")
        ));
        when(embeddingModel.embed("退款规则")).thenThrow(new RuntimeException("embedding unavailable"));

        String context = manager.getOptimizedContext("session-1", "退款规则");

        assertThat(context).contains("退款流程和售后规则");
        assertThat(context).doesNotContain("天气播报");
    }

    @Test
    void shouldClearSessionData() {
        manager.clearSession("session-1");

        verify(redisTemplate).delete("ai:history:session-1");
        verify(redisTemplate).delete("ai:summary:session-1");
    }

    private static Map<String, Object> summary(String text, float[] embedding) {
        Map<String, Object> summary = new HashMap<>();
        summary.put("summary", text);
        summary.put("embedding", embedding);
        return summary;
    }

    private static Map<String, Object> legacySummary(String text) {
        Map<String, Object> summary = new HashMap<>();
        summary.put("summary", text);
        return summary;
    }
}
