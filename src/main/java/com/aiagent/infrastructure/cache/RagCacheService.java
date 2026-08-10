package com.aiagent.infrastructure.cache;

import com.aiagent.knowledge.domain.RetrievalChunk;
import jakarta.annotation.PostConstruct;
import com.aiagent.infrastructure.metrics.PlatformMetricsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class RagCacheService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final PlatformMetricsService metricsService;

    private static final String CACHE_PREFIX = "ai:rag-cache:";
    private static final long CACHE_TTL_HOURS = 1;

    @PostConstruct
    public void init() {
        log.info("RAG cache service initialized, TTL: {}h", CACHE_TTL_HOURS);
    }

    @SuppressWarnings("unchecked")
    public List<RetrievalChunk> getCachedResults(String query) {
        String key = CacheKeyUtil.buildKey(CACHE_PREFIX, query);
        List<RetrievalChunk> result = CacheExceptionHandler.safeRead("RAG", () -> {
            Object cached = redisTemplate.opsForValue().get(key);
            return cached instanceof List ? (List<RetrievalChunk>) cached : null;
        });
        metricsService.recordRagCache(result != null);
        return result;
    }
    public void cacheResults(String query, List<RetrievalChunk> results) {
        if (results == null || results.isEmpty()) {
            return;
        }
        String key = CacheKeyUtil.buildKey(CACHE_PREFIX, query);
        CacheExceptionHandler.safeWrite("RAG", () -> {
            redisTemplate.opsForValue().set(key, results, CACHE_TTL_HOURS, TimeUnit.HOURS);
        });
    }
}
