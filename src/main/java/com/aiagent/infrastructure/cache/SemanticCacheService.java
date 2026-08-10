package com.aiagent.infrastructure.cache;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import jakarta.annotation.PostConstruct;
import com.aiagent.infrastructure.metrics.PlatformMetricsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 语义缓存服务 — 降低 API 调用成本
 *
 * <p>核心逻辑：
 * <ol>
 *   <li>用户提问时，先计算问题的 embedding</li>
 *   <li>与缓存中已有问题的 embedding 计算余弦相似度</li>
 *   <li>若相似度 > 阈值（默认 0.92），直接返回缓存结果</li>
 *   <li>否则调用 LLM 获取回答，并将新问题与回答存入缓存</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SemanticCacheService {

    private final EmbeddingModel embeddingModel;
    private final RedisTemplate<String, Object> redisTemplate;
    private final EmbeddingCacheService embeddingCacheService;
    private final PlatformMetricsService metricsService;

    private static final String CACHE_PREFIX = "ai:semantic-cache:";
    private static final String CACHE_INDEX = CACHE_PREFIX + "index";
    private static final double SIMILARITY_THRESHOLD = 0.92;
    private static final long CACHE_TTL_HOURS = 24;

    @PostConstruct
    public void init() {
        log.info("Semantic cache service initialized, similarity threshold: {}", SIMILARITY_THRESHOLD);
    }

    public String getIfCached(String question) {
        var sample = metricsService.startSample();
        boolean hit = false;
        double bestScore = 0;
        try {
            Embedding queryEmbedding = getEmbedding(question);
            if (queryEmbedding == null) {
                return null;
            }

            Set<Object> cachedKeys = redisTemplate.opsForSet().members(CACHE_INDEX);
            if (cachedKeys == null || cachedKeys.isEmpty()) {
                return null;
            }

            String bestMatch = null;
            for (Object keyObj : cachedKeys) {
                String cacheKey = String.valueOf(keyObj);
                Map<String, Object> entry = CacheExceptionHandler.safeRead("Semantic", () -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> value = (Map<String, Object>) redisTemplate.opsForValue().get(cacheKey);
                    return value;
                });

                if (entry == null) {
                    redisTemplate.opsForSet().remove(CACHE_INDEX, cacheKey);
                    continue;
                }

                Object embeddingValue = entry.get("embedding");
                Object answerValue = entry.get("answer");
                if (!(embeddingValue instanceof float[] cachedEmbedding) || !(answerValue instanceof String cachedAnswer)) {
                    log.warn("Ignoring malformed semantic cache entry: {}", cacheKey);
                    continue;
                }

                double similarity = cosineSimilarity(queryEmbedding.vector(), cachedEmbedding);
                if (similarity > bestScore) {
                    bestScore = similarity;
                    if (similarity >= SIMILARITY_THRESHOLD) {
                        bestMatch = cachedAnswer;
                    }
                }
            }

            if (bestMatch != null) {
                hit = true;
                log.info("Semantic cache hit, similarity: {}", String.format("%.4f", bestScore));
                return bestMatch;
            }

            log.info("Semantic cache miss, best similarity: {}", String.format("%.4f", bestScore));
            return null;
        } finally {
            metricsService.recordSemanticCache(hit, bestScore, sample);
        }
    }
    public void put(String question, String answer) {
        CacheExceptionHandler.safeWrite("Semantic", () -> {
            Embedding embedding = getEmbedding(question);
            if (embedding == null) {
                log.warn("Semantic cache write failed: cannot get embedding");
                return;
            }

            String cacheKey = CACHE_PREFIX + Math.abs(question.hashCode());

            Map<String, Object> entry = Map.of(
                    "question", question,
                    "answer", answer,
                    "embedding", embedding.vector(),
                    "timestamp", System.currentTimeMillis()
            );

            redisTemplate.opsForValue().set(cacheKey, entry, CACHE_TTL_HOURS, TimeUnit.HOURS);
            redisTemplate.opsForSet().add(CACHE_INDEX, cacheKey);

            log.info("Semantic cache written: {}", cacheKey);
        });
    }

    private Embedding getEmbedding(String text) {
        float[] cached = embeddingCacheService.getCachedEmbedding(text);
        if (cached != null) {
            log.debug("Embedding cache hit: text={}", text);
            return new Embedding(cached);
        }

        return CacheExceptionHandler.safeExecute("Embedding computation", () -> {
            Embedding embedding = embeddingModel.embed(text).content();
            embeddingCacheService.cacheEmbedding(text, embedding.vector());
            return embedding;
        }, null);
    }

    public void clear() {
        Set<Object> cachedKeys = redisTemplate.opsForSet().members(CACHE_INDEX);
        if (cachedKeys != null) {
            cachedKeys.forEach(key -> redisTemplate.delete((String) key));
        }
        redisTemplate.delete(CACHE_INDEX);
        log.info("Semantic cache cleared");
    }

    private double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) {
            log.warn("Vector dimension mismatch: {} vs {}", a.length, b.length);
            return 0;
        }
        double dotProduct = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dotProduct += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        double magnitude = Math.sqrt(normA) * Math.sqrt(normB);
        return magnitude == 0 ? 0 : dotProduct / magnitude;
    }
}
