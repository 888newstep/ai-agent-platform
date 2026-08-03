package com.aiagent.cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EmbeddingCacheServiceTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    private EmbeddingCacheService embeddingCacheService;

    @BeforeEach
    void setUp() {
        embeddingCacheService = new EmbeddingCacheService(redisTemplate);
    }

    @Test
    void shouldReturnNullWhenCacheMiss() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);

        float[] result = embeddingCacheService.getCachedEmbedding("test text");

        assertNull(result);
    }

    @Test
    void shouldReturnCachedEmbeddingWhenHit() {
        float[] cachedVector = new float[]{1.0f, 2.0f, 3.0f};
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(cachedVector);

        float[] result = embeddingCacheService.getCachedEmbedding("test text");

        assertNotNull(result);
        assertArrayEquals(cachedVector, result);
    }

    @Test
    void shouldCacheEmbeddingSuccessfully() {
        float[] vector = new float[]{1.0f, 2.0f, 3.0f};
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        embeddingCacheService.cacheEmbedding("test text", vector);

        verify(valueOperations).set(anyString(), eq(vector), eq(24L), eq(TimeUnit.HOURS));
    }

    @Test
    void shouldHandleRedisExceptionGracefully() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenThrow(new RuntimeException("Redis error"));

        float[] result = embeddingCacheService.getCachedEmbedding("test text");

        assertNull(result);
    }

    @Test
    void shouldHandleCacheWriteExceptionGracefully() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        doThrow(new RuntimeException("Redis write error"))
                .when(valueOperations).set(anyString(), any(), anyLong(), any(TimeUnit.class));

        float[] vector = new float[]{1.0f, 2.0f};
        assertDoesNotThrow(() -> embeddingCacheService.cacheEmbedding("test", vector));
    }
}
