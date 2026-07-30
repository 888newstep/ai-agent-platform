package com.aiagent.cache;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import jakarta.annotation.PostConstruct;
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
 *   <li>用户提问时，先计算问题 embedding</li>
 *   <li>与缓存中已有问题的 embedding 计算余弦相似度</li>
 *   <li>若相似度 > 阈值（默认 0.92），直接返回缓存结果</li>
 *   <li>否则调用 LLM 获取回答，并将新问题与回答存入缓存</li>
 * </ol>
 *
 * <p>面试价值：
 * <ul>
 *   <li>Q154 大模型调用成本控制 — 语义缓存方案</li>
 *   <li>体现工程化思维：减少 API 调用 ≈ 降低成本 + 降低延迟</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SemanticCacheService {

    private final EmbeddingModel embeddingModel;
    private final RedisTemplate<String, Object> redisTemplate;

    /** 缓存前缀 */
    private static final String CACHE_PREFIX = "ai:semantic-cache:";

    /** 缓存索引 key（存储所有缓存问题的 key 列表） */
    private static final String CACHE_INDEX = CACHE_PREFIX + "index";

    /** 相似度阈值 */
    private static final double SIMILARITY_THRESHOLD = 0.92;

    /** 缓存 TTL（小时） */
    private static final long CACHE_TTL_HOURS = 24;

    @PostConstruct
    public void init() {
        log.info("语义缓存服务初始化完成，相似度阈值: {}", SIMILARITY_THRESHOLD);
    }

    /**
     * 尝试从语义缓存中获取回答
     *
     * @param question 用户问题
     * @return 如果命中缓存返回回答，否则返回 null
     */
    public String getIfCached(String question) {
        // 1. 计算问题 embedding
        Embedding queryEmbedding;
        try {
            queryEmbedding = embeddingModel.embed(question).content();
        } catch (Exception e) {
            log.warn("Embedding 计算失败，跳过语义缓存: {}", e.getMessage());
            return null;
        }

        // 2. 获取所有缓存索引
        Set<Object> cachedKeys = redisTemplate.opsForSet().members(CACHE_INDEX);
        if (cachedKeys == null || cachedKeys.isEmpty()) {
            return null;
        }

        // 3. 遍历缓存，计算相似度
        String bestMatch = null;
        double bestScore = 0;

        for (Object keyObj : cachedKeys) {
            String cacheKey = (String) keyObj;
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> entry = (Map<String, Object>) redisTemplate.opsForValue().get(cacheKey);
                if (entry == null) {
                    // 缓存已过期，清理索引
                    redisTemplate.opsForSet().remove(CACHE_INDEX, cacheKey);
                    continue;
                }

                float[] cachedEmbedding = (float[]) entry.get("embedding");
                String cachedAnswer = (String) entry.get("answer");

                // 计算余弦相似度
                double similarity = cosineSimilarity(queryEmbedding.vector(), cachedEmbedding);
                if (similarity > bestScore) {
                    bestScore = similarity;
                    if (similarity >= SIMILARITY_THRESHOLD) {
                        bestMatch = cachedAnswer;
                    }
                }
            } catch (Exception e) {
                log.warn("语义缓存读取失败: {}", e.getMessage());
            }
        }

        if (bestMatch != null) {
            log.info("✅ 语义缓存命中，相似度: {:.4f}", bestScore);
            return bestMatch;
        }

        log.info("语义缓存未命中，最佳相似度: {:.4f}", bestScore);
        return null;
    }

    /**
     * 将问题与回答存入语义缓存
     *
     * @param question 用户问题
     * @param answer   模型回答
     */
    public void put(String question, String answer) {
        try {
            Embedding embedding = embeddingModel.embed(question).content();

            String cacheKey = CACHE_PREFIX + Math.abs(question.hashCode());

            Map<String, Object> entry = Map.of(
                    "question", question,
                    "answer", answer,
                    "embedding", embedding.vector(),
                    "timestamp", System.currentTimeMillis()
            );

            redisTemplate.opsForValue().set(cacheKey, entry, CACHE_TTL_HOURS, TimeUnit.HOURS);
            redisTemplate.opsForSet().add(CACHE_INDEX, cacheKey);

            log.info("语义缓存已写入: {}", cacheKey);
        } catch (Exception e) {
            log.warn("语义缓存写入失败: {}", e.getMessage());
        }
    }

    /**
     * 清空语义缓存
     */
    public void clear() {
        Set<Object> cachedKeys = redisTemplate.opsForSet().members(CACHE_INDEX);
        if (cachedKeys != null) {
            cachedKeys.forEach(key -> redisTemplate.delete((String) key));
        }
        redisTemplate.delete(CACHE_INDEX);
        log.info("语义缓存已清空");
    }

    /**
     * 计算两个 float 数组的余弦相似度
     */
    private double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) {
            log.warn("向量维度不匹配: {} vs {}", a.length, b.length);
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