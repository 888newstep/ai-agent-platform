package com.aiagent.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Configuration
@EnableCaching
@RequiredArgsConstructor
public class PerformanceConfig {

    private final AiProperties aiProperties;

    @Bean
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        AiProperties.Cache cacheConfig = aiProperties.getPerformance().getCache();

        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofSeconds(cacheConfig.getTtl()))
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(new GenericJackson2JsonRedisSerializer()))
                .disableCachingNullValues();

        Map<String, RedisCacheConfiguration> configMap = new HashMap<>();
        configMap.put("session", defaultConfig.entryTtl(Duration.ofHours(24)));
        configMap.put("semantic", defaultConfig.entryTtl(Duration.ofHours(1)));
        configMap.put("rag", defaultConfig.entryTtl(Duration.ofMinutes(30)));
        configMap.put("embedding", defaultConfig.entryTtl(Duration.ofHours(24)));
        configMap.put("config", defaultConfig.entryTtl(Duration.ofDays(7)));

        log.info("Cache manager initialized with {} custom cache strategies", configMap.size());

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaultConfig)
                .withInitialCacheConfigurations(configMap)
                .transactionAware()
                .build();
    }
}
