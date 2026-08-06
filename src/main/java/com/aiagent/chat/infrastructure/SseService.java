package com.aiagent.chat.infrastructure;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * SSE 服务 — 虚拟线程 + 心跳机制
 *
 * <p>核心功能：
 * <ul>
 *   <li>使用虚拟线程管理 SSE 连接，减少线程开销（Q218）</li>
 *   <li>心跳机制：每 15 秒发送 `:heartbeat` 防止 Nginx 超时断开（Q153）</li>
 *   <li>连接追踪：自动清理已断开的连接</li>
 *   <li>超时设置：适配 spring.mvc.async.request-timeout</li>
 * </ul>
 *
 * <p>面试价值：
 * <ul>
 *   <li>Q151 SSE 流式输出实现</li>
 *   <li>Q152 SSE vs WebSocket 区别</li>
 *   <li>Q153 如何保证 SSE 连接稳定性</li>
 *   <li>Q218 虚拟线程底层原理</li>
 * </ul>
 */
@Slf4j
@Service
public class SseService {

    /** 心跳间隔（秒） */
    private static final long HEARTBEAT_INTERVAL_SECONDS = 15;

    /** 连接超时（毫秒），需与 spring.mvc.async.request-timeout 一致 */
    private static final long SSE_TIMEOUT_MS = 300_000;

    /** 活跃的 SSE 连接 */
    private final Map<String, SseEmitter> activeEmitters = new ConcurrentHashMap<>();

    /** 心跳调度器 */
    private final ScheduledExecutorService heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(
            r -> {
                Thread t = new Thread(r, "sse-heartbeat");
                t.setDaemon(true);
                return t;
            }
    );

    public SseService() {
        // 启动心跳任务
        heartbeatScheduler.scheduleAtFixedRate(
                this::sendHeartbeats,
                HEARTBEAT_INTERVAL_SECONDS,
                HEARTBEAT_INTERVAL_SECONDS,
                TimeUnit.SECONDS
        );
        log.info("SSE 服务初始化完成，心跳间隔: {}s", HEARTBEAT_INTERVAL_SECONDS);
    }

    /**
     * 创建 SSE 连接
     *
     * @param sessionId 会话 ID
     * @return SseEmitter
     */
    public SseEmitter createEmitter(String sessionId) {
        // 设置超时时间
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);

        activeEmitters.put(sessionId, emitter);

        // 完成回调
        emitter.onCompletion(() -> {
            log.info("SSE 连接完成: sessionId={}", sessionId);
            activeEmitters.remove(sessionId);
        });

        // 超时回调
        emitter.onTimeout(() -> {
            log.warn("SSE 连接超时: sessionId={}", sessionId);
            activeEmitters.remove(sessionId);
        });

        // 错误回调
        emitter.onError(e -> {
            log.warn("SSE 连接错误: sessionId={}, error={}", sessionId, e.getMessage());
            activeEmitters.remove(sessionId);
        });

        log.info("SSE 连接创建: sessionId={}, 活跃连接数: {}", sessionId, activeEmitters.size());
        return emitter;
    }

    /**
     * 发送消息
     */
    public boolean send(String sessionId, String data) {
        SseEmitter emitter = activeEmitters.get(sessionId);
        if (emitter == null) {
            return false;
        }
        try {
            emitter.send(SseEmitter.event()
                    .name("message")
                    .data(data));
            return true;
        } catch (IOException e) {
            log.warn("SSE 发送失败: sessionId={}, error={}", sessionId, e.getMessage());
            activeEmitters.remove(sessionId);
            return false;
        }
    }

    /**
     * 发送事件
     */
    public boolean sendEvent(String sessionId, String eventName, Object data) {
        SseEmitter emitter = activeEmitters.get(sessionId);
        if (emitter == null) {
            return false;
        }
        try {
            emitter.send(SseEmitter.event()
                    .name(eventName)
                    .data(data));
            return true;
        } catch (IOException e) {
            log.warn("SSE 发送事件失败: sessionId={}, event={}, error={}", sessionId, eventName, e.getMessage());
            activeEmitters.remove(sessionId);
            return false;
        }
    }

    /**
     * 完成连接
     */
    public void complete(String sessionId) {
        SseEmitter emitter = activeEmitters.remove(sessionId);
        if (emitter != null) {
            emitter.complete();
        }
    }

    /**
     * 发送错误并关闭
     */
    public void completeWithError(String sessionId, Throwable error) {
        SseEmitter emitter = activeEmitters.remove(sessionId);
        if (emitter != null) {
            emitter.completeWithError(error);
        }
    }

    /**
     * 获取活跃连接数
     */
    public int getActiveCount() {
        return activeEmitters.size();
    }

    /**
     * 向所有活跃连接发送心跳
     * SSE 心跳协议：发送以 ":" 开头的注释行，客户端会自动忽略
     * 但 Nginx 会将此视为有效数据传输，重置超时计时器
     */
    private void sendHeartbeats() {
        if (activeEmitters.isEmpty()) {
            return;
        }

        log.debug("SSE 心跳: 向 {} 个活跃连接发送心跳", activeEmitters.size());

        activeEmitters.forEach((sessionId, emitter) -> {
            try {
                // 发送心跳注释（SSE 协议标准心跳方式）
                emitter.send(SseEmitter.event()
                        .comment("heartbeat")
                        .data(""));
            } catch (IOException e) {
                log.warn("SSE 心跳发送失败: sessionId={}, 已移除", sessionId);
                activeEmitters.remove(sessionId);
            }
        });
    }

    @PreDestroy
    public void destroy() {
        log.info("SSE 服务关闭，清理 {} 个活跃连接", activeEmitters.size());
        heartbeatScheduler.shutdown();
        activeEmitters.forEach((sessionId, emitter) -> {
            try {
                emitter.complete();
            } catch (Exception ignored) {
            }
        });
        activeEmitters.clear();
    }
}