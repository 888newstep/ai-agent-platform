package com.aiagent.cache;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddingCacheService {

    private final RedisTemplate<String, Object> redisTemplate;

    private static final String CACHE_PREFIX = "ai:embedding:";
    private static final long CACHE_TTL_HOURS = 24;

    @PostConstruct
    public void init() {
        log.info("Embedding cache service initialized, TTL: {}h", CACHE_TTL_HOURS);
    }

    public float[] getCachedEmbedding(String text) {
        String key = CacheKeyUtil.buildKey(CACHE_PREFIX, text);
        return CacheExceptionHandler.safeRead("Embedding", () -> {
            Object cached = redisTemplate.opsForValue().get(key);
            return cached instanceof float[] ? (float[]) cached : null;
        });
    }

    public void cacheEmbedding(String text, float[] vector) {
        String key = CacheKeyUtil.buildKey(CACHE_PREFIX, text);
        CacheExceptionHandler.safeWrite("Embedding", () -> {
            redisTemplate.opsForValue().set(key, vector, CACHE_TTL_HOURS, TimeUnit.HOURS);
        });
    }
}
