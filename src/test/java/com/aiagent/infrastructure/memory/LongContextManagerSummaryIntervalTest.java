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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LongContextManagerSummaryIntervalTest {

    private static final int TOTAL_TURNS = 30;

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ChatLanguageModel chatLanguageModel;

    @Mock
    private EmbeddingModel embeddingModel;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    private final Map<String, Object> redisStore = new HashMap<>();
    private final Map<String, AtomicLong> redisCounters = new HashMap<>();

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
    @SuppressWarnings("unchecked")
    void shouldSummarizeOncePerIntervalAfterSlidingWindowIsFull() {
        stubRedisAndModels();

        saveTurns("session-1", TOTAL_TURNS);

        int summaryInterval = aiProperties.getSession().getSummaryInterval();
        verify(chatLanguageModel, times(TOTAL_TURNS / summaryInterval)).generate(anyString());
        List<Map<String, Object>> summaries = (List<Map<String, Object>>) redisStore.get("ai:summary:session-1");
        assertThat(summaries).hasSize(TOTAL_TURNS / summaryInterval);
        assertThat(summaries.get(summaries.size() - 1).get("round")).isEqualTo((long) TOTAL_TURNS);
    }

    @Test
    void shouldKeepSummarizingWhenIntervalDoesNotDivideSlidingWindowSize() {
        aiProperties.getSession().setSummaryInterval(4);
        stubRedisAndModels();

        saveTurns("session-1", TOTAL_TURNS);

        verify(chatLanguageModel, times(TOTAL_TURNS / 4)).generate(anyString());
    }

    private void saveTurns(String sessionId, int turns) {
        for (int index = 0; index < turns; index++) {
            manager.saveMessageAndMaybeSummarize(sessionId, index % 2 == 0 ? "user" : "assistant", "消息" + index);
        }
    }

    private void stubRedisAndModels() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString()))
                .thenAnswer(invocation -> redisStore.get(invocation.getArgument(0, String.class)));
        doAnswer(invocation -> {
            redisStore.put(invocation.getArgument(0, String.class), invocation.getArgument(1));
            return null;
        }).when(valueOperations).set(anyString(), any(), anyLong(), any(TimeUnit.class));
        when(valueOperations.increment(anyString())).thenAnswer(invocation -> redisCounters
                .computeIfAbsent(invocation.getArgument(0, String.class), key -> new AtomicLong())
                .incrementAndGet());
        when(chatLanguageModel.generate(anyString())).thenReturn("生成的摘要");
        when(embeddingModel.embed("生成的摘要")).thenReturn(new Response<>(new Embedding(new float[]{1.0f, 0.2f})));
    }
}
