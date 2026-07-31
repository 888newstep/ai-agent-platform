package com.aiagent.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FallbackChatLanguageModel 降级逻辑测试
 *
 * 验证限流关键词检测是否正确，确保降级策略可靠。
 */
class FallbackChatLanguageModelTest {

    private final FallbackChatLanguageModel fallback = new FallbackChatLanguageModel(null, null);

    @Test
    void shouldDetectRateLimitByStatusCode() {
        Exception ex = new RuntimeException("429 Too Many Requests");
        assertTrue(fallback.isRateLimit(ex));
    }

    @Test
    void shouldDetectRateLimitByKeyword() {
        Exception ex = new RuntimeException("rate limit exceeded, please try again later");
        assertTrue(fallback.isRateLimit(ex));
    }

    @Test
    void shouldDetectRateLimitInNestedCause() {
        Exception inner = new RuntimeException("throttling: request frequency too high");
        Exception outer = new RuntimeException("API call failed", inner);
        assertTrue(fallback.isRateLimit(outer));
    }

    @Test
    void shouldNotDetectNonRateLimitError() {
        Exception ex = new RuntimeException("Internal server error");
        assertFalse(fallback.isRateLimit(ex));
    }

    @Test
    void shouldHandleNullMessageGracefully() {
        Exception ex = new RuntimeException((String) null);
        assertFalse(fallback.isRateLimit(ex));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "429 Too Many Requests",
            "rate limit exceeded",
            "too many requests, slow down",
            "throttling applied",
            "rate_limit_exceeded",
            "请求频率过高，请稍后再试",
            "API 限流"
    })
    void shouldDetectAllRateLimitPatterns(String message) {
        Exception ex = new RuntimeException(message);
        assertTrue(fallback.isRateLimit(ex));
    }
}