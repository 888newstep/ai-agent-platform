package com.aiagent.infrastructure.cache;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import com.aiagent.infrastructure.metrics.PlatformMetricsService;
import io.micrometer.core.instrument.Timer;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 閻犲浂鍘虹粻鐔虹磽閹惧磭鎽犻柡鍫濈Т婵喎霉鐎ｎ厾妲?
 *
 * 濡ょ姴鐭侀惁澶岀磽閹惧磭鎽犻柛娑欏灊閼?闁哄牜浜滈幊鈩冪▔椤撯懇鍋撻弰蹇曞竼闁靛棔绀佺槐鎾舵暜缁嬫妲遍柣鐐叉閳?
 */
@ExtendWith(MockitoExtension.class)
class SemanticCacheServiceTest {

    @Mock
    private EmbeddingModel embeddingModel;

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private EmbeddingCacheService embeddingCacheService;

    @Mock
    private SetOperations<String, Object> setOps;

    @Mock
    private ValueOperations<String, Object> valueOps;

    @Mock
    private PlatformMetricsService metricsService;

    @org.junit.jupiter.api.BeforeEach
    void setupMetrics() {
        org.mockito.Mockito.lenient().when(metricsService.startSample()).thenReturn(Timer.start());
    }

    @Test
    void shouldReturnNullWhenCacheIsEmpty() {
        // 婵☆垪鍓濈€?embedding 缂傚倹鎸搁悺銊╁嫉椤忓嫭鍤掑☉?
        when(embeddingCacheService.getCachedEmbedding(anyString())).thenReturn(null);
        // 婵☆垪鍓濈€?embedding 闁瑰瓨鍔曟慨?
        float[] vector = new float[1024];
        when(embeddingModel.embed(anyString()))
                .thenReturn(new Response<>(new Embedding(vector)));
        // 婵☆垪鍓濈€氭瑧绮氶搹鍦閻庢稒顭囬崒銊ヮ嚕?
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        when(setOps.members(anyString())).thenReturn(Set.of());

        SemanticCacheService cacheService = new SemanticCacheService(embeddingModel, redisTemplate, embeddingCacheService, metricsService);
        assertNull(cacheService.getIfCached("test question"));
        verify(metricsService).recordCacheOperation("semantic", "get", false);
    }

    @Test
    void shouldReturnNullWhenEmbeddingFails() {
        when(embeddingCacheService.getCachedEmbedding(anyString())).thenReturn(null);
        when(embeddingModel.embed(anyString())).thenThrow(new RuntimeException("API error"));

        SemanticCacheService cacheService = new SemanticCacheService(embeddingModel, redisTemplate, embeddingCacheService, metricsService);
        assertNull(cacheService.getIfCached("test question"));
    }

    @Test
    void shouldHandleCachePutSuccessfully() {
        float[] vector = new float[1024];
        vector[0] = 1.0f;
        when(embeddingCacheService.getCachedEmbedding(anyString())).thenReturn(null);
        when(embeddingModel.embed(anyString()))
                .thenReturn(new Response<>(new Embedding(vector)));

        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(redisTemplate.opsForSet()).thenReturn(setOps);

        SemanticCacheService cacheService = new SemanticCacheService(embeddingModel, redisTemplate, embeddingCacheService, metricsService);
        assertDoesNotThrow(() -> cacheService.put("test question", "test answer"));
        verify(valueOps).set(anyString(), anyMap(), eq(24L), eq(TimeUnit.HOURS));
        verify(setOps).add(anyString(), anyString());
    }

    @Test
    void shouldHandleCachePutFailureGracefully() {
        when(embeddingCacheService.getCachedEmbedding(anyString())).thenReturn(null);
        when(embeddingModel.embed(anyString())).thenThrow(new RuntimeException("Model unavailable"));

        SemanticCacheService cacheService = new SemanticCacheService(embeddingModel, redisTemplate, embeddingCacheService, metricsService);
        assertDoesNotThrow(() -> cacheService.put("test question", "test answer"));
    }

    @Test
    void shouldReturnCachedAnswerWhenSimilarityAboveThreshold() {
        float[] queryVector = new float[1024];
        queryVector[0] = 1.0f;
        float[] cachedVector = new float[1024];
        cachedVector[0] = 0.95f;

        when(embeddingCacheService.getCachedEmbedding(anyString())).thenReturn(null);
        when(embeddingModel.embed(anyString()))
                .thenReturn(new Response<>(new Embedding(queryVector)));
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        when(setOps.members(anyString())).thenReturn(Set.of("ai:semantic-cache:123"));
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        java.util.Map<String, Object> cachedEntry = java.util.Map.of(
                "question", "similar question",
                "answer", "cached answer",
                "embedding", cachedVector
        );
        when(valueOps.get("ai:semantic-cache:123")).thenReturn(cachedEntry);

        SemanticCacheService cacheService = new SemanticCacheService(embeddingModel, redisTemplate, embeddingCacheService, metricsService);
        String result = cacheService.getIfCached("test question");

        assertEquals("cached answer", result);
        verify(metricsService).recordCacheOperation("semantic", "get", true);
    }

    @Test
    void shouldIgnoreEntriesFromAnotherNamespace() {
        float[] vector = new float[4];
        vector[0] = 1.0f;
        when(embeddingCacheService.getCachedEmbedding(anyString())).thenReturn(vector);
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        when(setOps.members(anyString())).thenReturn(Set.of("ai:semantic-cache:123"));
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("ai:semantic-cache:123")).thenReturn(java.util.Map.of(
                "namespace", "react:rag",
                "question", "question",
                "answer", "react answer",
                "embedding", vector
        ));

        SemanticCacheService cacheService = new SemanticCacheService(
                embeddingModel, redisTemplate, embeddingCacheService, metricsService);

        assertNull(cacheService.getIfCached("normal:rag", "question"));
    }

    @Test
    void shouldReturnNullWhenSimilarityBelowThreshold() {
        float[] queryVector = new float[4];
        queryVector[0] = 1.0f;
        queryVector[1] = 0.0f;
        queryVector[2] = 0.0f;
        queryVector[3] = 0.0f;
        float[] cachedVector = new float[4];
        cachedVector[0] = 0.0f;
        cachedVector[1] = 1.0f;
        cachedVector[2] = 0.0f;
        cachedVector[3] = 0.0f;

        when(embeddingCacheService.getCachedEmbedding(anyString())).thenReturn(null);
        when(embeddingModel.embed(anyString()))
                .thenReturn(new Response<>(new Embedding(queryVector)));
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        when(setOps.members(anyString())).thenReturn(Set.of("ai:semantic-cache:123"));
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        java.util.Map<String, Object> cachedEntry = java.util.Map.of(
                "question", "different question",
                "answer", "cached answer",
                "embedding", cachedVector
        );
        when(valueOps.get("ai:semantic-cache:123")).thenReturn(cachedEntry);

        SemanticCacheService cacheService = new SemanticCacheService(embeddingModel, redisTemplate, embeddingCacheService, metricsService);
        String result = cacheService.getIfCached("test question");

        assertNull(result);
    }

    @Test
    void shouldClearAllCachedEntries() {
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        when(setOps.members(anyString())).thenReturn(Set.of("key1", "key2", "key3"));

        SemanticCacheService cacheService = new SemanticCacheService(embeddingModel, redisTemplate, embeddingCacheService, metricsService);
        cacheService.clear();

        verify(redisTemplate).delete("key1");
        verify(redisTemplate).delete("key2");
        verify(redisTemplate).delete("key3");
        verify(redisTemplate).delete("ai:semantic-cache:index");
    }

    @Test
    void shouldUseCachedEmbeddingWhenAvailable() {
        float[] cachedEmbedding = new float[1024];
        cachedEmbedding[0] = 1.0f;

        when(embeddingCacheService.getCachedEmbedding("test question"))
                .thenReturn(cachedEmbedding);
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        when(setOps.members(anyString())).thenReturn(Set.of());

        SemanticCacheService cacheService = new SemanticCacheService(embeddingModel, redisTemplate, embeddingCacheService, metricsService);
        cacheService.getIfCached("test question");

        verify(embeddingModel, never()).embed(anyString());
    }
}
