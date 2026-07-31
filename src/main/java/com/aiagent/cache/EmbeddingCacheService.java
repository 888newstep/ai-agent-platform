package com.aiagent.cache;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.TimeUnit;

/**
 * Embedding 缓存服务 — 缓存文本的 embedding 向量，避免重复调用 embedding API（省钱 + 降延迟）
 *
 * <p>核心逻辑：
 * <ol>
 *   <li>计算文本的 MD5 作为缓存 key</li>
 *   <li>如果 Redis 中有缓存，直接返回缓存向量，跳过 API 调用</li>
 *   <li>否则调用 embedding API，将结果存入 Redis（24h TTL）</li>
 * </ol>
 *
 * <p>面试价值：
 * <ul>
 *   <li>减少 API 调用次数 → 降低成本 + 降低延迟</li>
 *   <li>相同的用户问题频繁出现时，embedding 直接复用，无需调 SiliconFlow API</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddingCacheService {

    private final RedisTemplate<String, Object> redisTemplate;

    private static final String CACHE_PREFIX = "ai:embedding:";
    private static final long CACHE_TTL_HOURS = 24;

    @PostConstruct
    public void init() {
        log.info("Embedding 缓存服务初始化完成，TTL: {}h", CACHE_TTL_HOURS);
    }

    /**
     * 获取缓存的 embedding 向量
     *
     * @param text 原始文本
     * @return 缓存的向量，如果没有缓存返回 null
     */
    public float[] getCachedEmbedding(String text) {
        String key = buildKey(text);
        try {
            Object cached = redisTemplate.opsForValue().get(key);
            if (cached instanceof float[]) {
                return (float[]) cached;
            }
            return null;
        } catch (Exception e) {
            log.warn("Embedding 缓存读取失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 缓存 embedding 向量到 Redis
     */
    public void cacheEmbedding(String text, float[] vector) {
        String key = buildKey(text);
        try {
            redisTemplate.opsForValue().set(key, vector, CACHE_TTL_HOURS, TimeUnit.HOURS);
        } catch (Exception e) {
            log.warn("Embedding 缓存写入失败: {}", e.getMessage());
        }
    }

    private static String buildKey(String text) {
        return CACHE_PREFIX + md5(text);
    }

    private static String md5(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : digest) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(text.hashCode());
        }
    }
}