package com.aiagent.cache;

import com.aiagent.document.DocumentChunk;
import jakarta.annotation.PostConstruct;
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

    private static final String CACHE_PREFIX = "ai:rag-cache:";
    private static final long CACHE_TTL_HOURS = 1;

    @PostConstruct
    public void init() {
        log.info("RAG cache service initialized, TTL: {}h", CACHE_TTL_HOURS);
    }

    @SuppressWarnings("unchecked")
    public List<DocumentChunk> getCachedResults(String query) {
        String key = CacheKeyUtil.buildKey(CACHE_PREFIX, query);
        try {
            Object cached = redisTemplate.opsForValue().get(key);
            if (cached instanceof List) {
                return (List<DocumentChunk>) cached;
            }
            return null;
        } catch (Exception e) {
            log.warn("RAG cache read failed: {}", e.getMessage());
            return null;
        }
    }

    public void cacheResults(String query, List<DocumentChunk> results) {
        if (results == null || results.isEmpty()) {
            return;
        }
        String key = CacheKeyUtil.buildKey(CACHE_PREFIX, query);
        try {
            redisTemplate.opsForValue().set(key, results, CACHE_TTL_HOURS, TimeUnit.HOURS);
        } catch (Exception e) {
            log.warn("RAG cache write failed: {}", e.getMessage());
        }
    }
}
