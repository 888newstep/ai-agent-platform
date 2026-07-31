package com.aiagent.cache;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
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
 * 语义缓存服务测试
 *
 * 验证缓存命中/未命中逻辑、异常处理。
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

    @Test
    void shouldReturnNullWhenCacheIsEmpty() {
        // 模拟 embedding 缓存未命中
        when(embeddingCacheService.getCachedEmbedding(anyString())).thenReturn(null);
        // 模拟 embedding 成功
        float[] vector = new float[1024];
        when(embeddingModel.embed(anyString()))
                .thenReturn(new Response<>(new Embedding(vector)));
        // 模拟空缓存索引
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        when(setOps.members(anyString())).thenReturn(Set.of());

        SemanticCacheService cacheService = new SemanticCacheService(embeddingModel, redisTemplate, embeddingCacheService);
        assertNull(cacheService.getIfCached("test question"));
    }

    @Test
    void shouldReturnNullWhenEmbeddingFails() {
        when(embeddingCacheService.getCachedEmbedding(anyString())).thenReturn(null);
        when(embeddingModel.embed(anyString())).thenThrow(new RuntimeException("API error"));

        SemanticCacheService cacheService = new SemanticCacheService(embeddingModel, redisTemplate, embeddingCacheService);
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

        SemanticCacheService cacheService = new SemanticCacheService(embeddingModel, redisTemplate, embeddingCacheService);
        assertDoesNotThrow(() -> cacheService.put("test question", "test answer"));
        verify(valueOps).set(anyString(), anyMap(), eq(24L), eq(TimeUnit.HOURS));
        verify(setOps).add(anyString(), anyString());
    }

    @Test
    void shouldHandleCachePutFailureGracefully() {
        when(embeddingCacheService.getCachedEmbedding(anyString())).thenReturn(null);
        when(embeddingModel.embed(anyString())).thenThrow(new RuntimeException("Model unavailable"));

        SemanticCacheService cacheService = new SemanticCacheService(embeddingModel, redisTemplate, embeddingCacheService);
        assertDoesNotThrow(() -> cacheService.put("test question", "test answer"));
    }
}