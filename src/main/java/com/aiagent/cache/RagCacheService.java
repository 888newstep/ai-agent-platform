package com.aiagent.cache;

import com.aiagent.document.DocumentChunk;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * RAG 检索结果缓存服务 — 缓存多路召回结果，避免重复查询 Milvus + BM25
 *
 * <p>核心逻辑：
 * <ol>
 *   <li>对用户查询计算 MD5 作为缓存 key</li>
 *   <li>如果 Redis 中有缓存，直接返回检索结果，跳过 Milvus 查询和 BM25 计算</li>
 *   <li>否则执行多路召回，将结果存入 Redis（1h TTL）</li>
 * </ol>
 *
 * <p>面试价值：
 * <ul>
 *   <li>减少 Milvus 向量检索次数 → 降低延迟 + 减轻数据库压力</li>
 *   <li>相同的查询重复出现时，秒级返回 RAG 结果</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagCacheService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String CACHE_PREFIX = "ai:rag-cache:";
    private static final long CACHE_TTL_HOURS = 1;

    @PostConstruct
    public void init() {
        log.info("RAG 检索缓存服务初始化完成，TTL: {}h", CACHE_TTL_HOURS);
    }

    /**
     * 获取缓存的 RAG 检索结果
     *
     * @param query 用户查询
     * @return 缓存的文档片段列表，如果没有缓存返回 null
     */
    @SuppressWarnings("unchecked")
    public List<DocumentChunk> getCachedResults(String query) {
        String key = buildKey(query);
        try {
            Object cached = redisTemplate.opsForValue().get(key);
            if (cached instanceof List) {
                return (List<DocumentChunk>) cached;
            }
            return null;
        } catch (Exception e) {
            log.warn("RAG 缓存读取失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 缓存 RAG 检索结果到 Redis
     */
    public void cacheResults(String query, List<DocumentChunk> results) {
        if (results == null || results.isEmpty()) {
            return;
        }
        String key = buildKey(query);
        try {
            redisTemplate.opsForValue().set(key, results, CACHE_TTL_HOURS, TimeUnit.HOURS);
        } catch (Exception e) {
            log.warn("RAG 缓存写入失败: {}", e.getMessage());
        }
    }

    private static String buildKey(String query) {
        return CACHE_PREFIX + md5(query);
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