package com.aiagent.cache;

import com.aiagent.document.DocumentChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RagCacheServiceTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    private RagCacheService ragCacheService;

    @BeforeEach
    void setUp() {
        ragCacheService = new RagCacheService(redisTemplate);
    }

    @Test
    void shouldReturnNullWhenCacheMiss() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);

        List<DocumentChunk> result = ragCacheService.getCachedResults("test query");

        assertNull(result);
    }

    @Test
    void shouldReturnCachedResultsWhenHit() {
        List<DocumentChunk> cachedChunks = List.of(
                DocumentChunk.builder().id("1").content("content1").build(),
                DocumentChunk.builder().id("2").content("content2").build()
        );
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(cachedChunks);

        List<DocumentChunk> result = ragCacheService.getCachedResults("test query");

        assertNotNull(result);
        assertEquals(2, result.size());
    }

    @Test
    void shouldCacheResultsSuccessfully() {
        List<DocumentChunk> chunks = List.of(
                DocumentChunk.builder().id("1").content("content").build()
        );
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        ragCacheService.cacheResults("test query", chunks);

        verify(valueOperations).set(anyString(), eq(chunks), eq(1L), eq(TimeUnit.HOURS));
    }

    @Test
    void shouldNotCacheEmptyResults() {
        ragCacheService.cacheResults("test query", List.of());
        ragCacheService.cacheResults("test query", null);

        verify(valueOperations, never()).set(anyString(), any(), anyLong(), any(TimeUnit.class));
    }

    @Test
    void shouldHandleRedisExceptionGracefully() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenThrow(new RuntimeException("Redis error"));

        List<DocumentChunk> result = ragCacheService.getCachedResults("test query");

        assertNull(result);
    }
}
