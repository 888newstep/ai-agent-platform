package com.aiagent.config;

/**
 * Redis Key 设计规范
 *
 * 设计原则：
 * - Redis 只负责热缓存，不负责持久化
 * - 所有数据在 MySQL 有完整记录
 * - Key 统一前缀: ai:{domain}:{identifier}
 * - TTL 控制: 热数据 24h, 临时数据 1h~5min
 *
 * Key 模式一览：
 * ┌──────────────────────────────────────┬──────────────────────┬────────┐
 * │ Key                                  │ 用途                 │ TTL    │
 * ├──────────────────────────────────────┼──────────────────────┼────────┤
 * │ ai:session:{sessionId}               │ 会话历史(最近100条)   │ 24h   │
 * │ ai:lock:session:{sessionId}          │ 会话并发锁           │ 30s   │
 * │ ai:classify:hash:{questionMd5}       │ 分类结果缓存          │ 1h    │
 * │ ai:vision:{imageHash}                │ 图片分析结果缓存      │ 1h    │
 * │ ai:ratelimit:user:{userId}           │ 用户限流计数          │ 1s    │
 * │ ai:degrade:model:{modelName}         │ 模型降级标记          │ 5min  │
 * └──────────────────────────────────────┴──────────────────────┴────────┘
 */
public final class RedisKeyConstants {

    private RedisKeyConstants() {
        // 工具类，禁止实例化
    }

    // =============================================
    // Key 前缀
    // =============================================

    /** 会话历史缓存 */
    public static final String PREFIX_SESSION = "ai:session:";

    /** 会话并发锁 */
    public static final String PREFIX_SESSION_LOCK = "ai:lock:session:";

    /** 消息分类结果缓存 */
    public static final String PREFIX_CLASSIFY = "ai:classify:hash:";

    /** 视觉分析结果缓存 */
    public static final String PREFIX_VISION = "ai:vision:";

    /** 用户限流计数 */
    public static final String PREFIX_RATE_LIMIT = "ai:ratelimit:user:";

    /** 模型降级标记 */
    public static final String PREFIX_DEGRADE = "ai:degrade:model:";

    // =============================================
    // TTL 常量（单位：秒）
    // =============================================

    /** 会话历史缓存 TTL: 24小时 */
    public static final long TTL_SESSION = 86400L;

    /** 会话锁 TTL: 30秒 */
    public static final long TTL_SESSION_LOCK = 30L;

    /** 分类结果缓存 TTL: 1小时 */
    public static final long TTL_CLASSIFY = 3600L;

    /** 视觉分析缓存 TTL: 1小时 */
    public static final long TTL_VISION = 3600L;

    /** 限流窗口 TTL: 1秒 */
    public static final long TTL_RATE_LIMIT = 1L;

    /** 模型降级标记 TTL: 5分钟 */
    public static final long TTL_DEGRADE = 300L;

    // =============================================
    // Key 构建方法
    // =============================================

    public static String sessionKey(String sessionId) {
        return PREFIX_SESSION + sessionId;
    }

    public static String sessionLockKey(String sessionId) {
        return PREFIX_SESSION_LOCK + sessionId;
    }

    public static String classifyKey(String questionMd5) {
        return PREFIX_CLASSIFY + questionMd5;
    }

    public static String visionKey(String imageHash) {
        return PREFIX_VISION + imageHash;
    }

    public static String rateLimitKey(Long userId) {
        return PREFIX_RATE_LIMIT + userId;
    }

    public static String degradeKey(String modelName) {
        return PREFIX_DEGRADE + modelName;
    }
}