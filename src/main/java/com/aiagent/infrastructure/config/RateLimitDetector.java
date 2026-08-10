package com.aiagent.infrastructure.config;

import java.util.List;

public final class RateLimitDetector {

    private RateLimitDetector() {
    }

    private static final List<String> RATE_LIMIT_KEYWORDS = List.of(
            "429",
            "rate limit",
            "too many requests",
            "throttling",
            "rate_limit_exceeded",
            "请求过于频繁",
            "请求频率超限"
    );

    public static boolean isRateLimit(Throwable throwable) {
        if (throwable == null || throwable.getMessage() == null) {
            return false;
        }
        String msg = throwable.getMessage().toLowerCase();
        return RATE_LIMIT_KEYWORDS.stream().anyMatch(msg::contains);
    }

    public static boolean isRateLimit(Exception exception) {
        return isRateLimit((Throwable) exception);
    }
}
