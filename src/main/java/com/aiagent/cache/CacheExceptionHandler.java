package com.aiagent.cache;

import lombok.extern.slf4j.Slf4j;

import java.util.function.Supplier;

/**
 * 缓存操作异常处理工具类
 * 
 * 提供安全的缓存操作方法，统一异常处理逻辑
 */
@Slf4j
public final class CacheExceptionHandler {

    private CacheExceptionHandler() {
        // 工具类，禁止实例化
    }

    /**
     * 安全执行缓存读取操作
     * 
     * @param operation 操作名称（用于日志）
     * @param supplier 实际操作
     * @param <T> 返回类型
     * @return 操作结果，异常时返回 null
     */
    public static <T> T safeRead(String operation, Supplier<T> supplier) {
        try {
            return supplier.get();
        } catch (Exception e) {
            log.warn("{} cache read failed: {}", operation, e.getMessage());
            return null;
        }
    }

    /**
     * 安全执行缓存写入操作
     * 
     * @param operation 操作名称（用于日志）
     * @param runnable 实际操作
     */
    public static void safeWrite(String operation, Runnable runnable) {
        try {
            runnable.run();
        } catch (Exception e) {
            log.warn("{} cache write failed: {}", operation, e.getMessage());
        }
    }

    /**
     * 安全执行缓存操作（带默认值）
     * 
     * @param operation 操作名称（用于日志）
     * @param supplier 实际操作
     * @param defaultValue 异常时的默认值
     * @param <T> 返回类型
     * @return 操作结果，异常时返回默认值
     */
    public static <T> T safeExecute(String operation, Supplier<T> supplier, T defaultValue) {
        try {
            return supplier.get();
        } catch (Exception e) {
            log.warn("{} cache operation failed: {}", operation, e.getMessage());
            return defaultValue;
        }
    }
}
