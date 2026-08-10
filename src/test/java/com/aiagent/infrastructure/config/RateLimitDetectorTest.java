package com.aiagent.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RateLimitDetector 的限流错误识别测试。
 * 覆盖状态码、英文关键词、中文关键词和空值边界。
 */
class RateLimitDetectorTest {

    @Test
    void shouldDetectRateLimitByStatusCode() {
        Exception ex = new RuntimeException("429 Too Many Requests");
        assertTrue(RateLimitDetector.isRateLimit(ex));
    }

    @Test
    void shouldDetectRateLimitByKeyword() {
        Exception ex = new RuntimeException("rate limit exceeded, please try again later");
        assertTrue(RateLimitDetector.isRateLimit(ex));
    }

    @Test
    void shouldNotDetectNonRateLimitError() {
        Exception ex = new RuntimeException("Internal server error");
        assertFalse(RateLimitDetector.isRateLimit(ex));
    }

    @Test
    void shouldHandleNullMessageGracefully() {
        Exception ex = new RuntimeException((String) null);
        assertFalse(RateLimitDetector.isRateLimit(ex));
    }

    @Test
    void shouldHandleNullThrowable() {
        assertFalse(RateLimitDetector.isRateLimit((Throwable) null));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "429 Too Many Requests",
            "rate limit exceeded",
            "too many requests, slow down",
            "throttling applied",
            "rate_limit_exceeded",
            "请求过于频繁",
            "API 请求频率超限"
    })
    void shouldDetectAllRateLimitPatterns(String message) {
        Exception ex = new RuntimeException(message);
        assertTrue(RateLimitDetector.isRateLimit(ex));
    }
}
